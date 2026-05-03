# NPU 数据流性能测量报告

本文记录当前 toy 昇腾 NPU 在 16x16 Cube tile 下的主要性能结果。当前版本已经实现：

- 16x16 Cube tile 和 PE 两级 MAC 流水
- L0A/L0B tile FIFO、L0C 累加
- MTE1/MTE2/MTE3 三条数据流
- CopyInQueue / DMAQueue / CopyOutQueue
- `WAIT_DMA` / `WAIT_COPY_IN` / `WAIT_COPY_OUT` / `WAIT_ALL` token wait

## 测试方法

测试通过 ScalaTest/ChiselSim 运行：

```bash
sbt "set Test / parallelExecution := false" "testOnly ascend.*"
```

重点观察以下计数器：

| 计数器 | 含义 |
|---|---|
| `totalCycles` | kernel 从 start 到 halted 的周期 |
| `cubeComputeCycles` | SystolicArray 有效喂数计算周期 |
| `dmaTotalCycles` | MTE2 L2<->UB 周期 |
| `copyInCycles` | MTE1 UB->L0A/L0B 周期 |
| `copyOutCycles` | MTE3 L0C->UB 周期 |
| `dataflowOverlapCycles` | Cube 与任一 MTE 同时活跃周期 |

## 测试结果

| 场景 | totalCycles | Cube compute | MTE2 DMA | CopyIn | CopyOut | dataflow overlap | 平均每 tile |
|---|---:|---:|---:|---:|---:|---:|---:|
| 单 tile MATMUL | 347 | 46 | 144 | 98 | 16 | 94 | 347.0 |
| Pipeline3 3 tile | 855 | 138 | 432 | 294 | 48 | 182 | 285.0 |
| TripleBuffer 4 tile | 1129 | 184 | 576 | 392 | 64 | 250 | 282.3 |
| OverlapBenchmark 顺序版 | 1038 | 138 | 432 | 294 | 48 | 282 | 346.0 |
| OverlapBenchmark 流水版 | 832 | 138 | 432 | 294 | 48 | 282 | 277.3 |

## 解读

### 1. 访存延迟还没有被充分掩盖

单 tile 中，MTE2 DMA 为 144 cycles，Cube compute 为 46 cycles。即使调度完美，单 tile 也无法把全部外部搬运隐藏在计算后面。

多 tile 场景下，Pipeline3 和 TripleBuffer 通过预取和 L0 tile FIFO 增加了重叠，但 MTE2 总周期仍然远高于 Cube compute，总时间仍主要受数据流限制。

### 2. 队列化让数据搬运和计算可以同时存在

OverlapBenchmark 流水版比顺序版少 206 cycles：

```text
1038 - 832 = 206 cycles
```

这不是因为 DMA 工作量减少，而是因为下一 tile 的 DMA/CopyIn task 更早进入队列，与当前 tile 的计算重叠。

### 3. WAIT token 改善正确性表达

旧式全局 `DMA_WAIT` 只能表达“等所有 DMA”。当前 `WAIT_COPY_IN` 可以表达“UB 即将复用，必须等 MTE1 读完”，`WAIT_COPY_OUT` 可以表达“DMA_STORE 即将读 UB 结果，必须等 MTE3 写完”。这让性能程序里的同步边界更接近真实昇腾式 producer/consumer 调度。

## 当前瓶颈

- MTE2 一次只执行一个 L2<->UB task，外部搬运仍是主要瓶颈。
- MTE1/MTE3/VectorCore 在 toy 模型里共享 UB port A，CopyIn 与 CopyOut 不能真正同时使用该端口。
- token scoreboard 仍是队列级 pending，不是 tile id/address 级事件。
- 没有建模 UB/L2 bank conflict、NoC、stream、真实格式转换和 Fixpipe。

## 教学结论

当前结果说明：只增加 Cube 并不能自动获得高利用率。昇腾式 NPU 的关键是把数据搬运、片上缓存复用和 token 同步显式编排成流水线。这个 toy 项目现在能观察到这条规律：**MTE task queue 决定数据能否及时到达，Cube 决定数据到达后的峰值吞吐**。
