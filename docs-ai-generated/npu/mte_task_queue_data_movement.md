# MTE Task Queue 数据流

本项目将 NPU 数据搬运建模为独立 task queue，而不是 Scalar 指令的附属控制信号。目标是表达三个事实：

1. Cube 的峰值吞吐依赖稳定供数。
2. CopyIn、DMA、CopyOut 可以与 Compute 同时存在。
3. UB/L0 复用必须通过 token 或事件边界保证正确性。

## 数据流队列

```text
CopyInQueue  -> MTE1 -> UB -> L1 staging -> L0A/L0B
DMAQueue     -> MTE2 -> L2 <-> UB
CopyOutQueue -> MTE3 -> L0C -> UB
```

Scalar 负责提交任务和执行 `WAIT_*`；MTE 负责从队列取任务并执行。

| 指令 | 行为 |
|---|---|
| `LOAD` | 向 `CopyInQueue` 入队，不等待 MTE1 完成 |
| `STORE` | 向 `CopyOutQueue` 入队，不等待 MTE3 完成 |
| `DMA_LOAD` | 向 `DMAQueue` 入队，MTE2 后台执行 L2->UB |
| `DMA_STORE` | 向 `DMAQueue` 入队，MTE2 后台执行 UB->L2 |
| `WAIT_DMA` | 等待 MTE2 队列和引擎清空 |
| `WAIT_COPY_IN` | 等待 MTE1 队列和引擎清空 |
| `WAIT_COPY_OUT` | 等待 MTE3 队列和引擎清空 |
| `WAIT_ALL` | 等待三条数据流全部清空 |

## 启动规则

```text
queue non-empty && target MTE idle -> dequeue task -> pulse MTE.start
```

当前 toy 模型的资源约束：

- MTE1、MTE3 和 VectorCore 共享 UB port A。
- MTE2 独占 UB port B 和 L2 端口。
- CopyIn 和 CopyOut 同时 ready 时，CopyIn 优先。
- `DMA_STORE` 读取 UB 结果前必须等待相关 CopyOut 完成。

这些规则保留了 NPU 数据流的关键性质：队列化可以增加重叠，但仍必须受端口、buffer 和依赖约束限制。

## Task 类型

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

## 性能计数器

| 计数器 | 含义 |
|---|---|
| `copyInTaskCount` / `copyOutTaskCount` | MTE1/MTE3 实际取出的 task 数 |
| `dmaLoadTaskCount` / `dmaStoreTaskCount` | MTE2 实际取出的 load/store task 数 |
| `copyInCycles` / `dmaTotalCycles` / `copyOutCycles` | 三类数据搬运各自占用周期 |
| `copyInComputeOverlapCycles` | MTE1 与 Cube 同时活跃的周期 |
| `dmaComputeOverlapCycles` | MTE2 与 Cube 同时活跃的周期 |
| `copyOutComputeOverlapCycles` | MTE3 与 Cube 同时活跃的周期 |
| `dataflowOverlapCycles` | Cube 与任一 MTE 同时活跃的周期 |

## 代表性结果

| 场景 | totalCycles | MTE2 DMA | CopyIn | CopyOut | dataflow overlap | 平均每 tile |
|---|---:|---:|---:|---:|---:|---:|
| Pipeline3 3 tile | 855 | 432 | 294 | 48 | 182 | 285.0 |
| TripleBuffer 4 tile | 1129 | 576 | 392 | 64 | 250 | 282.3 |
| OverlapBenchmark 顺序版 | 1038 | 432 | 294 | 48 | 282 | 346.0 |
| OverlapBenchmark 流水版 | 832 | 432 | 294 | 48 | 282 | 277.3 |

流水版没有减少 CopyIn、DMA 或 CopyOut 的工作量；它减少的是串行等待时间。3 tile 总周期从 1038 降到 832，是因为后续 tile 的数据搬运更早进入队列，并与当前 tile 的计算重叠。

## 结论

计算阵列决定峰值上限；MTE task queue、片上 buffer 和 event/token 决定峰值能否转化为持续吞吐。
