# NPU 与 GPU 性能对比分析

## 1. 比较基准

本文比较的是本仓库中的教学级 NPU/GPU 模型，不是昇腾或 NVIDIA 真实芯片的性能定标。

| 项目 | 内容 |
|---|---|
| 代码基准 commit | `5c1aa910219a0f3d17f23712690ce68f54393a62` |
| 短 commit | `5c1aa91` |
| commit 时间 | `2026-05-03 14:56:32 +0800` |
| commit 说明 | `Implement NPU dataflow task queues and token waits` |
| 主要测试命令 | `sbt "testOnly ascend.IntegrationTest ascend.LargeMatmulTest gpu.GpuIntegrationTest"` |
| 补充测试命令 | `sbt "testOnly ascend.Pipeline3Test ascend.TripleBufferTest ascend.OverlapBenchmark"` |

测试结果：上述两组命令均通过。第一组运行 9 个测试，第二组运行 4 个测试。

需要特别说明：当前 NPU 模型已经实现 16x16 Cube tile、MTE task queue 和 token wait；当前 GPU 模型主要用于观察 SIMT/warp 调度和访存延迟隐藏，尚未实现 Tensor Core，也没有完整的 GPU 矩阵乘 kernel。因此，本文不能把 NPU 的矩阵乘周期与 GPU 的向量加法周期直接解释为真实硬件的矩阵性能排名。

## 2. 当前模型边界

| 维度 | Toy NPU | Toy GPU |
|---|---|---|
| 主要目标 | 16x16 tile 矩阵乘与显式数据流 | SIMT warp 调度与访存延迟隐藏 |
| 计算单元 | 16x16 SystolicArray，256 个 PE | 4 个 SM，每个 warp 4 个线程 |
| 专用矩阵单元 | Cube 路径已建模 | 未建模 Tensor Core |
| 数据搬运 | MTE1 CopyIn、MTE2 DMA、MTE3 CopyOut | HBM Controller + HBM Model、SharedMem、RegFile |
| 同步机制 | `WAIT_ALL` / `WAIT_DMA` / `WAIT_COPY_IN` / `WAIT_COPY_OUT` | warp ready/stall 调度 |
| 主要观察指标 | tile 周期、MTE/Cube 重叠、等待气泡 | live/eligible/stalled warp-cycle |

## 3. NPU：16x16 矩阵乘实测

### 3.1 测试程序

单 tile MATMUL 程序执行如下数据流：

```text
DMA_LOAD A
DMA_LOAD W
WAIT_ALL
LOAD A: UB -> L0A
LOAD W: UB -> L0B
MATMUL: L0A x L0B -> L0C
STORE: L0C -> UB
DMA_STORE: UB -> L2
WAIT_ALL
HALT
```

`LOAD`、`STORE`、`DMA_LOAD`、`DMA_STORE` 不再直接阻塞执行数据搬运，而是向对应 task queue 发任务；`WAIT` 在必要边界等待对应数据流清空。

### 3.2 周期计数

| 指标 | 数值 | 含义 |
|---|---:|---|
| `totalCycles` | 347 | kernel 从 start 到 halted 的 NPU 性能计数器周期 |
| 测试框架观测周期 | 348 | `runToHalt` 侧的外部观测值，含测试框架计数边界差异 |
| `cubeComputeCycles` | 46 | Cube 有效计算活跃周期 |
| `dmaTotalCycles` | 144 | MTE2 L2 与 UB 之间搬运周期 |
| `copyInCycles` | 98 | MTE1 从 UB 搬入 L0A/L0B 的周期 |
| `copyOutCycles` | 16 | MTE3 从 L0C 搬回 UB 的周期 |
| `dataflowOverlapCycles` | 94 | Cube 与任一 MTE 同时活跃的周期 |
| `bubbleCycles` | 278 | Scalar 等待或无可提交工作的周期 |

### 3.3 派生指标

```text
计算活跃占比        = 46 / 347  = 13.3%
MTE2 DMA 占比       = 144 / 347 = 41.5%
MTE2/Cube 重叠率    = 94 / 144  = 65.3%
16x16 MATMUL 运算量 = 16 x 16 x 16 = 4096 MAC
端到端有效吞吐      = 4096 / 347 = 11.8 MAC/cycle
理想阵列下界        = 4096 / 256 = 16 cycles
端到端理想利用率    = 16 / 347 = 4.6%
```

这组数据表明，当前 toy NPU 的主要瓶颈不是 PE 数量不足，而是数据供给和同步边界造成的端到端开销。Cube 已经提供 256 PE 的矩阵阵列，但单个 tile 无法把 MTE2、CopyIn、CopyOut 的全部搬运成本隐藏在计算之后。

## 4. NPU：多 tile 流水效果

队列化数据流的意义在多 tile 程序中更清楚。以下结果来自 `Pipeline3Test`、`TripleBufferTest` 和 `OverlapBenchmark`。

| 场景 | tile 数 | totalCycles | Cube compute | MTE2 DMA | CopyIn | CopyOut | dataflow overlap | 平均每 tile |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 单 tile MATMUL | 1 | 347 | 46 | 144 | 98 | 16 | 94 | 347.0 |
| Pipeline3 | 3 | 855 | 138 | 432 | 294 | 48 | 182 | 285.0 |
| TripleBuffer | 4 | 1129 | 184 | 576 | 392 | 64 | 250 | 282.3 |
| OverlapBenchmark 顺序版 | 3 | 1038 | 138 | 432 | 294 | 48 | 282 | 346.0 |
| OverlapBenchmark 流水版 | 3 | 832 | 138 | 432 | 294 | 48 | 282 | 277.3 |

`OverlapBenchmark` 中，流水版相对顺序版减少：

```text
1038 - 832 = 206 cycles
206 / 1038 = 19.8%
```

这说明 task queue 并没有减少总搬运工作量，而是让下一 tile 的 DMA/CopyIn 更早进入数据流，与当前 tile 的计算阶段重叠。平均每 tile 周期从 346.0 降至 277.3，体现的是显式数据流编排带来的吞吐改善。

同时，MTE2 DMA 仍然是主要瓶颈。以流水版为例，3 个 tile 的 MTE2 DMA 周期为 432，而 Cube compute 周期为 138；即使调度更积极，外部搬运仍不能被完全掩盖。

## 5. GPU：SIMT 向量加法实测

当前 GPU 测试运行 4 个 SM 的向量加法程序：

```text
LD R0, [base + 0]
LD R1, [base + 1]
ADD R2, R0, R1
ST [base + 2], R2
HALT
```

以下表格报告 `gpu.GpuIntegrationTest` 中 4-SM VADD 最后完成的 SM 计数。由于所有 SM 共享一个 HBM Controller，总周期包含控制器仲裁、bank/row 时序和排队。

### 5.1 HBM controller row-miss latency = 1

| 指标 | 数值 |
|---|---:|
| total cycles | 143 |
| live warp cycles | 423 |
| eligible warp cycles | 147 |
| stalled warp cycles | 276 |
| no-eligible cycles | 42 |
| ALU issue cycles | 4 |
| memory issue cycles | 12 |
| warp 占用率 | 74.0% |

### 5.2 HBM controller row-miss latency = 10

| 指标 | 数值 |
|---|---:|
| total cycles | 259 |
| live warp cycles | 820 |
| eligible warp cycles | 241 |
| stalled warp cycles | 579 |
| no-eligible cycles | 83 |
| ALU issue cycles | 4 |
| memory issue cycles | 12 |
| warp 占用率 | 79.1% |

访存延迟从 1 增加到 10 后，4-SM 总周期从 143 增加到 259。这个变化不是单个 warp 延迟的简单线性放大，而是 HBM Controller 的 bank/row 时序、请求排队和多个 SM 同时访存共同作用。单 SM 的 `DualSchedulerTest` 中，memory-dense latency=10 用例为 `totalCycles=110`、`stalledWarpCycles=205`、`noEligibleCycles=22`，说明 warp 切换仍能隐藏部分等待，但不能消除 HBM 侧排队。

## 6. 矩阵乘场景的可比性

当前 NPU 侧有 16x16 MATMUL 的实测周期；当前 GPU 侧没有 Tensor Core，也没有已验证的矩阵乘测试。因此，严格说本文只能比较两类模型在各自代表 workload 下的性能特征：

- NPU 数据说明：专用矩阵阵列必须依赖 MTE、片上缓存和 token 同步才能接近持续吞吐。
- GPU 数据说明：SIMT 调度器通过 warp 切换把一部分访存延迟转化为后台等待。
- 当前仓库数据不能证明“真实昇腾 Cube Core 一定快于或慢于真实 NVIDIA Tensor Core”。

若要在本仓库内进行更严格的矩阵乘对比，需要补齐至少两类测试：

1. 在 GPU 模型中实现朴素 SIMT 16x16 MATMUL，并记录 HBM-backed GlobalMem、SharedMem 和 MAD 指令周期。
2. 在 GPU 模型中加入 Tensor Core 类矩阵执行单元，再与 NPU Cube tile 在相同 tile 大小、相同数据类型、相同访存层次假设下比较。

## 7. 32x32 矩阵乘估算

当前 `LargeMatmulTest` 使用 16x16 tile 对 32x32 矩阵乘做上限估算：

```text
32x32 输出矩阵 = 2 x 2 个 16x16 输出 tile
每个输出 tile 在 K 方向需要 2 次 MATMUL/MATMUL_ACC
总 tile 计算次数 = 2 x 2 x 2 = 8
单 16x16 tile 外部观测约 348 cycles
顺序执行粗略上限 = 8 x 348 = 2784 cycles
理论 PE 周期下界 = (32 x 32 x 32) / 256 = 128 cycles
```

该估算不是完整 benchmark。它用于说明：矩阵规模变大后，tile 数和 K 方向累加次数迅速增加；是否能获得高吞吐，取决于 DMA、CopyIn、Compute、CopyOut 能否形成稳定流水，而不只是单次 MATMUL 的峰值能力。

## 8. 结论

1. 当前 NPU 模型的优势在于已经具备专用 16x16 Cube tile 和显式数据流机制，但端到端效率仍主要受 MTE2 搬运、CopyIn/CopyOut 和同步边界限制。
2. task queue 与 token wait 的价值体现在多 tile 场景：流水版 `OverlapBenchmark` 比顺序版减少 206 cycles，平均每 tile 从 346.0 cycles 降到 277.3 cycles。
3. 当前 GPU 模型同时展示 SIMT 延迟隐藏和 HBM 侧争用：4-SM VADD 在 `gmemLatency=10` 下会被共享 HBM Controller 的 bank/row 时序和排队拉长，单 SM 调度测试仍能看到部分访存等待被 ready warp 覆盖。
4. 当前仓库还不能做 Cube Core 与 Tensor Core 的直接性能结论。直接比较需要在同一模型内实现 GPU 矩阵乘或 Tensor Core，并统一数据类型、tile 大小、访存层次和调度策略。

## 9. 复现实验

```bash
# NPU 单 tile、32x32 估算、GPU VADD
sbt "testOnly ascend.IntegrationTest ascend.LargeMatmulTest gpu.GpuIntegrationTest"

# NPU 多 tile 流水与重叠测试
sbt "testOnly ascend.Pipeline3Test ascend.TripleBufferTest ascend.OverlapBenchmark"
```

相关源码：

- `src/main/scala/ascend/AscendParams.scala`
- `src/main/scala/ascend/AiCore.scala`
- `src/main/scala/ascend/ScalarUnit.scala`
- `src/main/scala/ascend/PerfCounters.scala`
- `src/main/scala/gpu/GpuParams.scala`
- `src/test/scala/ascend/IntegrationTest.scala`
- `src/test/scala/ascend/OverlapBenchmark.scala`
- `src/test/scala/gpu/GpuIntegrationTest.scala`
