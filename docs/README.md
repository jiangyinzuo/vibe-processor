# Vibe Processor 文档索引

本目录记录教学级 NPU/GPU RTL 的架构、ISA、性能、时序和设计思想。交互式架构图见 [interactive/index.html](interactive/index.html)。

## 架构要点

### GPU

- CTA/thread block、warp、SM 和共享寄存器文件。
- SM 级共享 CUDA Core，warp 只保存执行上下文。
- 双 Warp Scheduler 通过 ready/stalled 切换隐藏访存延迟。
- SFU 支持 EXP，ALU/SFU/MEM issue 计数可观测。

## 当前性能观察

- NPU `OverlapBenchmark` 流水版相对顺序版少 206 cycles，平均每 tile 从 346.0 cycles 降至 277.3 cycles。
- GPU VADD 在 `gmemLatency=10` 下有 88 个 stalled warp-cycle，但只有 14 个 no-eligible cycles。

详见 [性能对比](docs/performance_comparison.md) 和 [项目状态](PROJECT_STATUS.md)。

## 与真实硬件的差距

### GPU (vs NVIDIA A100)

| 特性 | 玩具版本 | NVIDIA A100 | 差距 |
|------|---------|-------------|------|
| SM 数量 | 4 | 108 | 27× |
| Warp 大小 | 4 | 32 | 8× |
| 调度器/SM | 2 | 4 | 2× |
| 架构模型 | 共享 CUDA Core | 共享 CUDA Core | **一致** |

- [ISA 指令集](isa.md)
- [NPU 架构](npu/architecture.md)
- [GPU 架构](gpu/architecture.md)
- [性能对比](performance_comparison.md)

### NPU 数据流

1. [昇腾式数据流设计思想](npu/ascend_dataflow_design.md)
2. [MTE task queue 数据流](npu/mte_task_queue_data_movement.md)
3. [event/token 同步](npu/event_token_synchronization.md)
4. [MTE-Compute Overlap](npu/dma_overlap.md)
5. [NPU 数据流性能测量](npu/performance_measurement.md)

### NPU Cube 与时序

1. [Cube 分形 Tile 格式](npu/fractal_tile_format.md)
2. [PE MAC 流水化](npu/pe_mac_pipeline.md)
3. [CubeCore 真实化优化](npu/cubecore_realism_optimization.md)
4. [NPU 流水线时序分析](npu/pipeline_timing_analysis.md)
5. [频率与周期联合评估](frequency_performance.md)

### GPU 调度与执行

1. [Warp 调度](gpu/warp_scheduling.md)
2. [双调度器实现](gpu/dual_scheduler_summary.md)
3. [共享 CUDA Core 架构](gpu/shared_architecture_summary.md)
4. [GPU 流水线时序分析](gpu/pipeline_timing_analysis.md)
5. [SFU 技术文档](gpu/sfu.md)

### 体系结构对比

1. [昇腾 NPU 与 NVIDIA GPU 设计思想](ascend_npu_nvidia_gpu_design_philosophy.md)
2. [NPU 与 GPU 性能对比](performance_comparison.md)
3. [GPU 架构对比](gpu/architecture_comparison.md)

## 文档清单

### 根目录

- `isa.md`：NPU/GPU 指令格式。
- `performance_comparison.md`：当前 toy 模型的性能对比和可比性边界。
- `frequency_performance.md`：cycles 与 Fmax 的联合估算。
- `ascend_npu_nvidia_gpu_design_philosophy.md`：从第一性原理解释两类芯片的设计思想。

### `docs/npu/`

- `architecture.md`：NPU 顶层架构、SPMD、存储、Cube、MTE 和性能计数器。
- `ascend_dataflow_design.md`：CopyIn/Compute/CopyOut 数据流思想。
- `mte_task_queue_data_movement.md`：MTE task queue 和数据搬运建模。
- `event_token_synchronization.md`：WAIT selector、token scoreboard 和 UB 复用边界。
- `dma_overlap.md`：MTE 与 Cube 的重叠机制。
- `performance_measurement.md`：当前数据流性能结果。
- `fractal_tile_format.md`：16x16 tile、pack/unpack 和 tail padding。
- `pe_mac_pipeline.md`：PE 乘法/加法流水。
- `cubecore_realism_optimization.md`：Cube 输入快照与 L0C 累加。
- `pipeline_timing_analysis.md`：Yosys LTP 与 OpenSTA 粗估。

### `docs/gpu/`

- `architecture.md`：CTA/thread block、SM、warp、寄存器和存储层次。
- `warp_scheduling.md`：Round-robin、协作式调度和延迟隐藏。
- `dual_scheduler_summary.md`：双 Warp Scheduler 的实现和计数器结果。
- `shared_architecture_summary.md`：共享 CUDA Core 重构。
- `architecture_comparison.md`：历史独占执行单元模型与当前共享模型的差异。
- `sfu.md`：EXP SFU 的实现和 ISA。
- `sfu_timing_fix.md`、`writeback_bug_fix.md`：SFU 集成过程中的时序与写回问题。
- `pipeline_timing_analysis.md`：GPU 侧时序粗估。

### `docs/interactive/`

- `index.html`：单文件交互式架构图。
- `README.md`：交互式架构图的开发和使用说明。

## 维护规则

- 性能数字更新时同步修改 `performance_comparison.md`、`PROJECT_STATUS.md` 和对应专题文档。
- 新增文档时在本索引添加入口。
- 对历史调试记录保留原因、修复和验证结果；删除“正在进行”“预计通过”等过时状态描述。
