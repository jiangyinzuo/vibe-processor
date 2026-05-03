# 教程：把数据搬运作为一等公民

真实昇腾式 NPU 的核心思想不是“Cube 很快，所以内存慢一点也没关系”。恰恰相反，Cube 很快，所以数据搬运必须被单独建模、单独调度、单独计数。否则教学项目会误导学生：好像只要有一个矩阵乘阵列，性能自然就来了。

本项目现在把数据搬运从 ScalarUnit 的直接控制信号中拆出来，改成三条独立的 MTE task queue：

```text
CopyInQueue  -> MTE1 -> UB -> L1 staging -> L0A/L0B
DMAQueue     -> MTE2 -> L2 <-> UB
CopyOutQueue -> MTE3 -> L0C -> UB
```

Scalar 只负责“发任务”和“等 token”。MTE 负责“按队列取任务并执行”。这比旧模型更接近昇腾的设计思想：**数据流是硬件执行路径，不是计算指令的附属动作**。

## 旧模型的问题

旧版 `ScalarUnit` 直接输出类似下面的控制信号：

```text
mte1Start, mte1DstSel, mte1UbAddr
mte3Start, mte3UbAddr
dmaQueueEnq, dmaEnqL2Addr, dmaEnqUbAddr
```

这会让教学模型看起来像：

```text
Scalar 一条条指挥每个搬运动作
MTE 只是 Scalar 指令的延长线
```

真实 NPU 里更重要的是 producer/consumer dataflow：

```text
下一 tile 的 DMA 可以提前入队
下一 tile 的 CopyIn 可以在当前 tile Compute 时执行
上一 tile 的 CopyOut 可以和后续数据准备错开
```

如果没有队列，学生很难看出这些动作是可以同时存在的“飞行中任务”。

## 新模型：三条 MTE task queue

当前 `AiCore` 中的任务类型是：

```scala
class CopyInTask extends Bundle {
  val dstSel = UInt(2.W)
  val ubBase = UInt(AscendParams.UBAddrW.W)
}

class DmaTask extends Bundle {
  val isStore = Bool()
  val l2Base = UInt(AscendParams.L2AddrW.W)
  val ubBase = UInt(AscendParams.UBAddrW.W)
}

class CopyOutTask extends Bundle {
  val ubBase = UInt(AscendParams.UBAddrW.W)
}
```

对应的指令行为变成：

| 指令 | 新行为 |
|---|---|
| `LOAD` | 向 `CopyInQueue` 入队，不等待 MTE1 执行完 |
| `STORE` | 向 `CopyOutQueue` 入队，不等待 MTE3 执行完 |
| `DMA_LOAD` | 向 `DMAQueue` 入队，MTE2 后台执行 L2->UB |
| `DMA_STORE` | 向 `DMAQueue` 入队，MTE2 后台执行 UB->L2 |
| `WAIT_*` | 等待对应 task queue 和 MTE 引擎清空 |

这让 Scalar 从“微操 MTE FSM”变成“提交数据流任务的调度器”。

## 队列如何启动 MTE

每条队列都有一个简单规则：

```text
队列非空 && 对应 MTE 空闲 -> dequeue task -> pulse MTE.start
```

MTE1/MTE3 在本项目中共享 UB port A，因此二者启动前还有一个 toy 级仲裁：

```text
MTE1/MTE3/VectorCore 不能同时占用 UB port A
CopyIn 和 CopyOut 同时 ready 时，CopyIn 优先
```

MTE2 独占 UB port B 和 L2 端口，所以它可以和 MTE1/MTE3/Cube 并行。这个简化模型保留了最重要的教学点：**片上数据通路有资源冲突，队列化以后仍然要靠仲裁和 token 保证正确性**。

另外，`DMA_STORE` 是 UB 的消费者；如果前面还有 `CopyOut` 未完成，MTE2 不会过早启动 store task。程序仍建议显式写 `WAIT_COPY_OUT`，因为这样依赖关系对读者和编译器都更清楚。

## 为什么这更接近昇腾

昇腾 AI Core 的高性能来自固定 tile 数据流：

```text
GM/L2/UB 的数据搬运
L0A/L0B 的 Cube 输入准备
L0C 的结果暂存和搬出
event/token 控制 producer/consumer 边界
```

Cube 只是流水线中的 Compute 段。真实调优里，程序员和编译器经常关心的是：

```text
下一个 tile 是否已经 CopyIn？
当前 tile 的 L0A/L0B 是否 ready？
上一 tile 的 L0C 是否已经 CopyOut？
哪一段 UB 可以复用？
MTE 和 Cube 是否真正重叠？
```

把 MTE task queue 显式化后，这些问题都能在代码和性能计数器里看到。

## 优化效果

队列化后，PerfCounters 不只统计指令数，还统计 task 和重叠周期：

| 计数器 | 含义 |
|---|---|
| `copyInTaskCount` / `copyOutTaskCount` | MTE1/MTE3 实际取出的 task 数 |
| `dmaLoadTaskCount` / `dmaStoreTaskCount` | MTE2 实际取出的 load/store task 数 |
| `copyInCycles` / `dmaTotalCycles` / `copyOutCycles` | 三类数据搬运各自占用周期 |
| `copyInComputeOverlapCycles` | MTE1 与 Cube 同时活跃的周期 |
| `dmaComputeOverlapCycles` | MTE2 与 Cube 同时活跃的周期 |
| `copyOutComputeOverlapCycles` | MTE3 与 Cube 同时活跃的周期 |
| `dataflowOverlapCycles` | Cube 与任一 MTE 同时活跃的周期 |

代表性结果：

| 场景 | totalCycles | MTE2 DMA | CopyIn | CopyOut | dataflow overlap | 平均每 tile |
|---|---:|---:|---:|---:|---:|---:|
| Pipeline3 3 tile | 855 | 432 | 294 | 48 | 182 | 285.0 |
| TripleBuffer 4 tile | 1129 | 576 | 392 | 64 | 250 | 282.3 |
| OverlapBenchmark 顺序版 | 1038 | 432 | 294 | 48 | 282 | 346.0 |
| OverlapBenchmark 流水版 | 832 | 432 | 294 | 48 | 282 | 277.3 |

对比重点不是 CopyIn/DMA/CopyOut 的总工作量变少了，而是这些工作不再必须跟 Compute 串行。流水版把 3 tile 总周期从 1038 降到 832，主要来自更早发出数据搬运任务，让 MTE2/MTE1 和 Cube 同时运行。

## 教学结论

这个改动突出了一条昇腾式设计思想：

```text
计算阵列决定峰值上限，
数据搬运队列、片上 buffer 和 event/token 决定能不能接近上限。
```

因此教学项目不应只展示 SystolicArray。它必须把 MTE、UB/L0、任务队列和同步 token 放在同等重要的位置。否则学生看到的是“一个快的矩阵乘法器”，而不是真正的 NPU 数据流机器。
