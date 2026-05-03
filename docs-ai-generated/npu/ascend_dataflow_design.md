# 昇腾式数据流设计思想教学记录

本文解释真实昇腾 NPU 背后的核心设计思想，并说明本项目如何用 toy NPU 把这些思想显式化。重点不是“Cube 会做矩阵乘”这一件事，而是：**数据怎样持续、规则、可预测地喂给 Cube**。

## 核心观点

真实昇腾 AI Core 的编程和硬件组织有一个很强的倾向：

```text
显式管理片上存储 + 显式编排数据搬运 + 固定 tile 高吞吐计算
```

也就是说，性能不只来自 Cube 里有多少个 MAC。性能来自整条数据流：

```text
CopyIn  ->  Compute  ->  CopyOut
L2/UB   ->  L0A/L0B  ->  L0C/UB/L2
MTE     ->  Cube     ->  MTE
```

CPU/GPU 编程里，很多访存细节可以被 cache、warp 调度或硬件乱序机制掩盖。昇腾式 NPU 更强调软件/编译器把 tile、buffer、event、pipeline 明确安排好，让硬件按规则高速执行。

## 三段流水

### CopyIn

CopyIn 的任务是把下一批 tile 搬到 Cube 附近。

在真实昇腾概念里，这通常涉及 GM/L2/UB/L1/L0A/L0B 等层次，以及 MTE 搬运和格式转换。本项目做了简化：

```text
MTE2: L2 <-> UB
MTE1: UB -> L1 staging -> L0A/L0B
```

教学重点是：CopyIn 应该尽量提前发生，不要等 Cube 空闲后才开始搬下一块数据。

### Compute

Compute 是 Cube 对 L0A/L0B tile 做矩阵乘，并把结果写到 L0C。

当前模型：

```text
L0A tile + L0B tile -> Cube input snapshot regs -> 16x16 SystolicArray -> L0C
```

`MATMUL` 支持 accumulate 位，所以 L0C 可以保存 K 方向分块的 partial sum：

```text
L0C := L0A * L0B
L0C := L0C + L0A * L0B
```

这比“每次 MATMUL 立刻写回外部内存”更接近真实 GEMM tiling。

### CopyOut

CopyOut 的任务是把 L0C 的结果搬出 Cube 结果缓冲。

本项目中：

```text
MTE3: L0C -> UB
MTE2: UB -> L2
```

真实系统里，CopyOut 往往还会和 Fixpipe、格式转换、量化/反量化、激活等后处理相关。本项目目前只做了结果搬运，后处理仍然简化。

## 为什么 event/token 很重要

如果只用全局 `DMA_WAIT`，程序会变成：

```text
搬完所有数据 -> 等待 -> 计算 -> 搬出 -> 等待
```

这种写法简单，但会浪费 Cube。真实昇腾式调度更像：

```text
copyIn(tile i+1) can run while compute(tile i)
compute(tile i) waits only for tile i's input event
copyOut(tile i-1) can run while later tiles are being prepared
```

也就是说，同步粒度应当围绕 tile/event，而不是围绕整个 DMA 队列。当前 toy NPU 已经把 opcode `0xA` 从粗粒度 `DMA_WAIT` 扩展为带 selector 的 `WAIT`：

| WAIT 类型 | 等待对象 | 用途 |
|---|---|---|
| `WAIT_DMA` | MTE2 L2↔UB task | `DMA_LOAD` 后确认 UB 数据 ready |
| `WAIT_COPY_IN` | MTE1 UB→L0A/L0B task | 复用 UB 前确认 MTE1 已读完 |
| `WAIT_COPY_OUT` | MTE3 L0C→UB task | `DMA_STORE` 前确认结果已写入 UB |
| `WAIT_ALL` | 三条数据流全部空闲 | kernel 结束前收尾 |

这套 token scoreboard 不是为了让所有程序都插入更多等待，而是为了让等待发生在真实依赖边界：消费者只等自己的生产者，其他数据流可以继续前进。

配套地，Scalar 不再直接驱动 MTE1/MTE3 的 start 信号，而是向三条 task queue 发任务：

```text
CopyInQueue  -> MTE1 -> L0A/L0B
DMAQueue     -> MTE2 -> L2/UB
CopyOutQueue -> MTE3 -> UB
```

详见 [event/token 同步教程](event_token_synchronization.md) 和 [MTE task queue 数据流教程](mte_task_queue_data_movement.md)。

## 本项目新增的可观察指标

为了让数据流思想不只停留在文档里，本次给 `PerfCounters` 增加了分阶段计数：

| 计数器 | 含义 |
|---|---|
| `copyInCycles` | MTE1 执行 UB -> L0A/L0B 的周期 |
| `dmaTotalCycles` | MTE2 执行 L2 <-> UB 的周期 |
| `copyOutCycles` | MTE3 执行 L0C -> UB 的周期 |
| `copyInTaskCount` / `copyOutTaskCount` | MTE1/MTE3 实际消费的 task 数 |
| `dmaLoadTaskCount` / `dmaStoreTaskCount` | MTE2 实际消费的 DMA task 数 |
| `copyInComputeOverlapCycles` | CopyIn 与 Cube 同时活跃的周期 |
| `dmaComputeOverlapCycles` | MTE2 DMA 与 Cube 同时活跃的周期 |
| `copyOutComputeOverlapCycles` | CopyOut 与 Cube 同时活跃的周期 |
| `dataflowOverlapCycles` | Cube 与任一 MTE 同时活跃的周期 |
| `waitDmaCycles` / `waitCopyInCycles` / `waitCopyOutCycles` | 不同 token wait 造成的停顿 |
| `overlapCycles` | 兼容旧文档的 MTE1/MTE2 与 Cube 重叠周期 |

这组指标把“访存有没有被掩盖”拆成了更清楚的问题：

- MTE2 有没有和 Cube 重叠？
- MTE1 CopyIn 有没有和 Cube 重叠？
- CopyOut 有没有成为串行尾巴？
- dataflow overlap 是来自真正的流水，还是只是某一个阶段偶然重叠？

## 当前效果

当前 16x16 tile、两级 PE MAC 流水下，关键测试结果如下：

| 场景 | totalCycles | Cube compute | MTE2 DMA | CopyIn | CopyOut | MTE/Cube overlap |
|---|---:|---:|---:|---:|---:|---:|
| 单 tile MATMUL | 347 | 46 | 144 | 98 | 16 | 94 |
| Pipeline3 3 tile | 855 | 138 | 432 | 294 | 48 | 182 |
| TripleBuffer 4 tile | 1129 | 184 | 576 | 392 | 64 | 250 |
| OverlapBenchmark 顺序版 | 1038 | 138 | 432 | 294 | 48 | 282 |
| OverlapBenchmark 流水版 | 832 | 138 | 432 | 294 | 48 | 282 |

解读：

- 单 tile 中，MTE2 DMA 为 144 cycles，Cube 有效计算为 46 cycles，所以最多只能掩盖约三分之一外部搬运。
- Pipeline3/TripleBuffer 已经能让 CopyIn/MTE2 和 Cube 重叠，但还不能完全隐藏搬运。
- OverlapBenchmark 流水版从 1038 cycles 降到 832 cycles，说明更早发出下一 tile 的数据流任务能显著减少总时间。
- OverlapBenchmark 的“顺序版”仍有较高重叠，因为 `LOAD`/`STORE` 已经是非阻塞 task enqueue；它缺少的是更积极的跨 tile 预取和更精确的 UB 复用安排。
- CopyOut 仍然比较短，但目前大多处在计算之后；后续引入多 L0C slot 或结果队列后，CopyOut 才能更充分并行。

## 与真实昇腾的差距

当前 toy NPU 已经能表达这几个关键思想：

- Cube 使用 L0A/L0B/L0C，而不是直接读写外部内存。
- MTE 和 Cube 是分开的执行路径。
- MTE1/MTE2/MTE3 都通过 task queue 消费数据流任务。
- `WAIT` 支持 DMA/CopyIn/CopyOut/All 四类 token。
- L0A/L0B 有 tile FIFO，可支持 CopyIn/Compute 重叠。
- L0C 支持 partial sum 累加。
- 性能计数器能观察三段数据流。

但它仍然缺少真实系统里很关键的部分：

- token 仍是队列/引擎级 pending，不是带 tile id、地址范围和依赖图的完整事件系统。
- MTE 队列仍是简单 FIFO，没有建模真实硬件里的多级队列、stream、barrier、bank 冲突和重排序能力。
- MTE 格式转换还没有真正进入硬件执行路径。
- CopyOut/Fixpipe 后处理仍然简化。
- 多核之间没有真实 NoC/L2 bank/stream 同步建模。

## 教学结论

昇腾式 NPU 的重点不是“把一个矩阵乘法器接到内存上”，而是把程序写成一条持续流动的 tile pipeline：

```text
提前 CopyIn，局部 Compute，延迟 CopyOut，
用 L0/UB 缓冲复用数据，
用 event/token 保证只在必要边界等待。
```

本项目已经把全局 `DMA_WAIT` 细化成数据流 token，并把 MTE1/MTE2/MTE3 接入 task queue。该模型强调：显式数据流编排与计算阵列同等重要。
