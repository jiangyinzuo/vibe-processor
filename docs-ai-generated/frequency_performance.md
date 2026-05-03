# 频率与周期联合评估

本文回答“只看仿真周期不够，是否能估算真实运行时间”的问题。

结论：

1. 可以粗估。当前推荐使用 `scripts/estimate_fmax.sh` 做综合后、布局布线前的 OpenSTA 频率估算。
2. 运行时间按 `time = cycles / Fmax` 估算；当 `Fmax` 单位是 MHz 时，`cycles / MHz` 的结果单位是微秒。
3. 这些结果不是最终签核频率。没有 floorplan/place/route 的线延迟，也没有真实 SRAM macro、时钟树和工艺约束，只适合早期比较和选择流水线切分点。

## 命令

先生成 Verilog，再对关键模块做 Yosys 映射和 OpenSTA：

```bash
scripts/estimate_fmax.sh AiCore ControlCpu CubeCore SystolicArray VectorCore Mte2 ScalarUnit
scripts/estimate_fmax.sh InstructionDispatcher CudaCore
```

结果写入：

```text
generated/sta/fmax/fmax_summary.csv
docs/frequency_fmax_summary.csv
generated/sta/fmax/<Module>_sta.log
```

其中 `generated/sta/fmax/fmax_summary.csv` 是临时生成产物，`docs/frequency_fmax_summary.csv` 是保存在仓库里的文档快照。脚本默认分析中等规模模块。`ToyAscendTop`、`ToyGpuTop`、`SM`、`SMSubPartition`、`SharedRegisterFile`、`SFU` 这类大模块也可以手动传入，但综合映射可能耗时很久。

## 计算方法

OpenSTA 报告里的最长 `data arrival time` 近似为当前模块的最小时钟周期：

```text
critical_path_ns = data arrival time
Fmax_MHz = 1000 / critical_path_ns
runtime_us = cycles / Fmax_MHz
```

例如 NPU 单核 8×8 MATMUL 集成测试为 182 cycles，当前 `CubeCore` 粗估 Fmax 约 3.32 MHz：

```text
runtime_us = 182 / 3.32 = 54.8 us
```

## 当前结果

环境：

```text
Yosys 0.33
OpenSTA 2.6.0
Liberty: sky130_fd_sc_hd slow corner, ss_100C_1v60
阶段: post-synthesis, pre-layout
```

### NPU

| 模块 | critical path | Fmax 粗估 | 结论 |
|---|---:|---:|---|
| `CubeCore` | 136.527 ns | 7.32 MHz | Cube 输入快照后仍是系统级瓶颈 |
| `AiCore` | 136.463 ns | 7.33 MHz | 已随 CubeCore 同步改善 |
| `SystolicArray` | 59.591 ns | 16.78 MHz | 下一层计算阵列瓶颈 |
| `CubeUnit` | 50.547 ns | 19.78 MHz | 独立模块不是当前系统瓶颈 |
| `VectorCore` | 28.141 ns | 35.54 MHz | 次要瓶颈 |
| `Mte2` | 19.342 ns | 51.70 MHz | 有压力但不是第一优先 |
| `ControlCpu` | 14.544 ns | 68.76 MHz | 调度控制不主导 NPU 频率 |
| `ScalarUnit` | 7.705 ns | 129.79 MHz | 当前不需要优先切 |

按当前最慢 `CubeCore` 估算：

| 场景 | cycles | 使用 Fmax | runtime 粗估 |
|---|---:|---:|---:|
| 单核 8×8 MATMUL | 182 | 7.32 MHz | 24.9 us |
| 2 核 SPMD `blockDim=4` | 367 | 7.32 MHz | 50.1 us |
| `Pipeline3Test` 3 tile | 447 | 7.32 MHz | 61.1 us |

如果后续把 `SystolicArray/PE` 切到更高频率，并让 `CubeCore` 继续跟随改善到接近当前 `SystolicArray` 独立水平，即约 16.78 MHz，单核 8×8 MATMUL 的粗估时间会变成：

```text
182 / 16.78 = 10.8 us
```

因此，继续拆 `CubeCore -> CubeUnit -> SystolicArray/PE` 的收益不仅会改变单次 MATMUL 的周期数，也会显著提高每周期对应的真实时间。Cube 输入快照的详细前后对比见 [CubeCore 真实化优化记录](npu/cubecore_realism_optimization.md)。

### GPU

当前重新跑通的 GPU 中等规模模块：

| 模块 | critical path | Fmax 粗估 | 结论 |
|---|---:|---:|---|
| `InstructionDispatcher` | 19.014 ns | 52.59 MHz | decode/issue 有压力 |
| `CudaCore` | 25.539 ns | 39.16 MHz | ALU 路径中等偏重 |

之前完整 GPU 时序分析中，`SM` flatten 后的 slow-corner data arrival 约 331.882 ns，即 Fmax 约 3.01 MHz；`SharedRegisterFile` 和 `SFU` 独立路径也在 37 ns 量级。因此评估 GPU 运行时间时，当前应优先采用完整 `SM` 结果，而不是只采用较轻的叶子模块结果。

| 场景 | cycles | 使用 Fmax | runtime 粗估 |
|---|---:|---:|---:|
| 4-SM VADD, latency=1 | 39 | 3.01 MHz | 13.0 us |
| CTA/thread/block ID 验证 | 83 | 3.01 MHz | 27.6 us |

GPU 的下一步提频优先级仍是：

```text
schedule/fetch -> decode -> register read -> execute -> writeback
```

也就是先切 `InstructionDispatcher -> SharedRegisterFile -> SMSubPartition` 这条完整执行链，再分别处理 `SharedRegisterFile` forwarding 和 `SFU` 近似计算路径。

## 使用建议

- 评估架构性能时同时记录 `cycles`、`critical_path_ns`、`Fmax_MHz`、`runtime_us`。
- 比较两个优化时，不要只比较 cycles。流水线切分可能增加 cycles，但如果 Fmax 提升更多，总 runtime 仍会下降。
- 当前 `CubeCore` 和 GPU `SM` 的完整路径非常重，顶层/大模块 STA 会比较慢；日常迭代可以先跑关键子模块，阶段性再跑完整模块。
