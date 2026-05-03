# MTE-Compute Overlap 优化

本文解释当前 toy NPU 如何让数据搬运和 Cube 计算重叠。这里的重点已经不只是旧版“DMA 非阻塞”，而是三条 MTE 数据流都作为 task queue 存在：

```text
CopyInQueue  -> MTE1 -> UB -> L0A/L0B
DMAQueue     -> MTE2 -> L2 <-> UB
CopyOutQueue -> MTE3 -> L0C -> UB
```

ScalarUnit 负责发任务，MTE 后台消费任务，CubeCore 消费 ready 的 L0A/L0B tile。程序通过 `WAIT_DMA` / `WAIT_COPY_IN` / `WAIT_COPY_OUT` / `WAIT_ALL` 在必要边界同步。

## 硬件基础

### 三条 MTE 队列

| 队列 | 消费者 | 数据路径 | 指令来源 |
|---|---|---|---|
| CopyInQueue | MTE1 | UB -> L1 staging -> L0A/L0B | `LOAD` |
| DMAQueue | MTE2 | L2 <-> UB | `DMA_LOAD` / `DMA_STORE` |
| CopyOutQueue | MTE3 | L0C -> UB | `STORE` |

队列非空且对应 MTE 空闲时，`AiCore` 自动发起任务。MTE1/MTE3/VectorCore 共享 UB port A，因此 CopyIn/CopyOut 需要在本地端口前仲裁；MTE2 独占 UB port B 和 L2 端口，因此可以与本地 CopyIn/Compute/CopyOut 重叠。

### L0 tile FIFO

CubeCore 内部有 4 个 L0A/L0B tile slot。MTE1 写满一组 activation+weight 后，该 slot 变为 ready。`MATMUL` 到达时，CubeCore 从最老 ready slot 启动 CubeUnit。

这使程序可以先把 tile0 放入 L0，再预取/CopyIn tile1，并让 `MATMUL(tile0)` 与 tile1 的数据准备重叠。

## 编程模式

### 单 tile

```asm
DMA_LOAD  ub=0,  l2=0
DMA_LOAD  ub=16, l2=16
WAIT_DMA
LOAD      L0_B, 0
LOAD      L0_A, 16
MATMUL
STORE     L0_C, 32
WAIT_COPY_OUT
DMA_STORE ub=32, l2=32
WAIT_ALL
HALT
```

### 跨 tile 流水

```asm
; tile0 进入 L0
DMA_LOAD tile0_A
DMA_LOAD tile0_W
WAIT_DMA
LOAD tile0_A_to_L0
LOAD tile0_W_to_L0
WAIT_COPY_IN          ; UB 可以安全复用

; tile1 的 DMA/CopyIn 可以和 tile0 的 compute 重叠
DMA_LOAD tile1_A
DMA_LOAD tile1_W
WAIT_DMA
LOAD tile1_A_to_L0
LOAD tile1_W_to_L0
MATMUL tile0

STORE tile0_C
WAIT_COPY_OUT
DMA_STORE tile0_C
```

`WAIT_COPY_IN` 不是为了降低性能，而是为了明确 UB 复用边界：同一段 UB 被下一次 DMA 覆盖前，必须确认 MTE1 已经读完旧 tile。

## 性能计数器

| 计数器 | 含义 |
|---|---|
| `dmaTotalCycles` | MTE2 执行 L2<->UB 的周期 |
| `copyInCycles` | MTE1 执行 UB->L0A/L0B 的周期 |
| `copyOutCycles` | MTE3 执行 L0C->UB 的周期 |
| `copyInComputeOverlapCycles` | CopyIn 与 Cube 同时活跃的周期 |
| `dmaComputeOverlapCycles` | MTE2 与 Cube 同时活跃的周期 |
| `copyOutComputeOverlapCycles` | CopyOut 与 Cube 同时活跃的周期 |
| `dataflowOverlapCycles` | Cube 与任一 MTE 同时活跃的周期 |

## 测试结果

当前 16x16 tile、两级 PE MAC 流水下：

| 场景 | totalCycles | Cube compute | MTE2 DMA | CopyIn | CopyOut | dataflow overlap |
|---|---:|---:|---:|---:|---:|---:|
| 单 tile MATMUL | 347 | 46 | 144 | 98 | 16 | 94 |
| Pipeline3 3 tile | 855 | 138 | 432 | 294 | 48 | 182 |
| TripleBuffer 4 tile | 1129 | 184 | 576 | 392 | 64 | 250 |
| OverlapBenchmark 顺序版 | 1038 | 138 | 432 | 294 | 48 | 282 |
| OverlapBenchmark 流水版 | 832 | 138 | 432 | 294 | 48 | 282 |

流水版 OverlapBenchmark 从 1038 cycles 降到 832 cycles，说明更早发出下一 tile 的数据搬运 task 可以减少总时间。另一方面，MTE2 DMA 周期仍显著大于 Cube compute 周期，说明当前 toy NPU 还不能充分隐藏访存延迟。

## 进一步优化方向

- 给 token 增加 tile id 或 UB 地址范围，避免队列级 pending 过于保守。
- 建模 UB/L2 bank 和端口冲突，让数据流调度的代价更真实。
- 让 MTE 格式转换进入硬件路径，而不仅是软件 pack/unpack helper。
- 增加更多 L0C/result queue，让 CopyOut 更容易和后续 Compute 重叠。

## 参考

- [event/token 同步教程](event_token_synchronization.md)
- [MTE task queue 数据流教程](mte_task_queue_data_movement.md)
- [昇腾式数据流设计思想](ascend_dataflow_design.md)
- [ISA 文档](../isa.md)
