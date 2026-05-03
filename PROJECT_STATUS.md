# 项目状态

最后更新：2026-05-03

本文是仓库当前功能面的简要索引，不作为发布验收报告。精确测试结果以本地 `sbt test` 输出为准。

## 核心功能

### NPU

- 16x16 Cube tile，256 个 PE，支持 INT8 到 INT32 矩阵乘。
- L0A/L0B/L0C、UB、L2/HBM 的分层存储模型。
- MTE1/MTE2/MTE3 三条数据搬运路径，接入 task queue。
- `WAIT_ALL`、`WAIT_DMA`、`WAIT_COPY_IN`、`WAIT_COPY_OUT` token wait。
- Control CPU 复用 SPMD block scheduler，支持多个逻辑 block 映射到物理 AiCore。
- AI CPU 辅助执行 L2 row task。

### GPU

- SIMT warp 执行模型，CTA/thread block 坐标可见。
- 4 个 SM，支持多 resident CTA。
- SM 级共享 CUDA Core、共享寄存器文件和双 Warp Scheduler。
- GlobalMem、SharedMem、SFU、ALU/MEM/SFU issue 计数器。
- 通过 eligible/stalled/no-eligible 计数观察访存延迟隐藏。

## 当前性能观察

### NPU 数据流

| 场景 | totalCycles | Cube compute | MTE2 DMA | CopyIn | CopyOut | dataflow overlap |
|---|---:|---:|---:|---:|---:|---:|
| 单 tile MATMUL | 347 | 46 | 144 | 98 | 16 | 94 |
| OverlapBenchmark 顺序版 | 1038 | 138 | 432 | 294 | 48 | 282 |
| OverlapBenchmark 流水版 | 832 | 138 | 432 | 294 | 48 | 282 |

流水版比顺序版少 206 cycles，主要收益来自更早提交下一 tile 的 DMA/CopyIn task。

### GPU 延迟隐藏

| 场景 | total | live warp-cycle | eligible | stalled | no-eligible | MEM issue |
|---|---:|---:|---:|---:|---:|---:|
| VADD, `gmemLatency=1` | 39 | 119 | 103 | 16 | 0 | 12 |
| VADD, `gmemLatency=10` | 50 | 190 | 102 | 88 | 14 | 12 |

`gmemLatency=10` 下 stalled warp-cycle 明显增加，但总周期只增加 11 cycles，说明部分访存等待被其它 ready warp 覆盖。

## 最近验证

```bash
sbt "testOnly ascend.IntegrationTest ascend.LargeMatmulTest gpu.GpuIntegrationTest"
sbt "testOnly ascend.Pipeline3Test ascend.TripleBufferTest ascend.OverlapBenchmark"
```

上述两组命令在 2026-05-03 均通过。

## 主要文档

- `README.md`：项目入口、环境和常用命令。
- `docs/isa.md`：NPU/GPU 指令格式。
- `docs/performance_comparison.md`：当前 NPU/GPU toy 模型性能对比。
- `docs/npu/architecture.md`：NPU 架构。
- `docs/npu/performance_measurement.md`：NPU 数据流性能计数。
- `docs/gpu/architecture.md`：GPU 架构。
- `docs/gpu/warp_scheduling.md`：Warp 调度和延迟隐藏。
- `docs/ascend_npu_nvidia_gpu_design_philosophy.md`：昇腾 NPU 与 NVIDIA GPU 设计思想。

## 与真实硬件的差距

| 维度 | Toy NPU/GPU | 真实硬件 |
|---|---|---|
| 规模 | 2 个 AiCore、4 个 SM、小容量片上存储 | 多十到上百计算单元，完整片上网络和大容量缓存 |
| 时序 | 教学级 RTL，部分模块有粗略 STA | 经过物理设计、时钟树、SRAM macro 和工艺签核 |
| 调度 | FIFO/task queue/round-robin 等简化模型 | 多级队列、scoreboard、barrier、NoC、bank conflict 处理 |
| AI 矩阵单元 | NPU Cube 已建模；GPU 未建模 Tensor Core | 昇腾 Cube 和 NVIDIA Tensor Core 都有完整硬件栈支持 |
