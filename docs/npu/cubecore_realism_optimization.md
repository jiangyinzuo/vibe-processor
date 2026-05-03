# CubeCore 真实化优化记录

本文记录本次对 toy NPU `CubeCore` 的优化：让 Cube 执行路径更接近真实昇腾 AI Core 的数据流，同时给出优化前后的周期计数和 Fmax 粗估。

## 背景

华为昇腾公开文档中，AI Core 的计算单元包括 Cube、Vector、Scalar；Cube 主要执行矩阵计算，左矩阵来自 L0A，右矩阵来自 L0B，L0C 保存矩阵乘结果和中间结果。存储层次文档也明确把 L0A/L0B 作为 Cube 输入，把 L0C 作为 Cube 输出，并在累加计算时作为输入的一部分。相关参考：

- [Compute Units - Ascend C Operator Development](https://www.hiascend.com/document/detail/en/canncommercial/800/opdevg/Ascendcopdevg/atlas_ascendc_10_0009.html)
- [存储单元 - Ascend C 算子开发](https://www.hiascend.com/document/detail/zh/CANNCommunityEdition/82RC1alpha002/opdevg/Ascendcopdevg/atlas_ascendc_10_0010.html)
- [硬件架构抽象 - Ascend C 算子开发](https://www.hiascend.com/document/detail/zh/canncommercial/80RC3/developmentguide/opdevg/Ascendcopdevg/atlas_ascendc_10_0015.html)

本项目在前一轮已经有 L0A/L0B tile FIFO、L0C、MTE1/MTE2/MTE3 和 CubeCore/VectorCore 解耦。本次补齐两个关键点：

1. **Cube 输入快照寄存器**：`CubeUnit` 在启动时锁存 L0A/L0B tile，后续 SystolicArray 只读取 Cube 内部输入寄存器。
2. **L0C 累加模式**：`MATMUL` 指令新增 accumulate 位，支持 `L0C := L0C + A * B`，使 L0C 不只是最终输出，也能作为分块 GEMM 的中间累加缓冲。

## 优化思想

优化前，`CubeCore` 的 L0 tile FIFO 读口直接组合连接到 `CubeUnit`/`SystolicArray` 的权重加载和激活喂入路径。综合后，`CubeCore` 顶层关键路径会把 L0 slot 选择、矩阵输入 mux、阵列喂数、PE 乘加连在一起，导致 `CubeCore` 和 `AiCore` 的 Fmax 粗估很低。

优化后，`CubeUnit` 在 `io.start` 当拍把 `io.weightData` 和 `io.actData` 锁存到 `weightTileReg` / `actTileReg`。这相当于在 L0A/L0B 和 Cube 执行阵列之间加了 operand staging：

```text
优化前：L0A/L0B slot -> CubeUnit skew feed/weight load -> SystolicArray/PE
优化后：L0A/L0B slot -> Cube input regs -> SystolicArray/PE
```

普通 `MATMUL` 的可见周期数没有变化，因为快照发生在原本的 Cube launch 周期内；但时序上切断了上游 L0 FIFO 到 PE 阵列的长组合路径。

L0C 累加模式使用 `MATMUL` 指令 bit 27：

```text
C-type (MATMUL): [31:28] opcode=0x4  [27] accumulate  [26:0] reserved

accumulate=0: L0C := A * B
accumulate=1: L0C := L0C + A * B
```

这让 toy NPU 能表达更接近真实 GEMM tiling 的行为：多个 K 方向 tile 的部分和可以在 L0C 内累加，最后再由 MTE3 写回 UB。

## 代码变更

| 文件 | 变更 |
|---|---|
| `src/main/scala/ascend/CubeUnit.scala` | 新增 `weightTileReg` / `actTileReg`，Cube 启动时锁存 L0A/L0B tile |
| `src/main/scala/ascend/CubeCore.scala` | 新增 `accumulate` 输入，Cube done 时支持写入或累加到 L0C |
| `src/main/scala/ascend/ScalarUnit.scala` | 解码 `MATMUL` bit 27 并输出 `cubeAccumulate` |
| `src/main/scala/ascend/AiCore.scala` | 连接 Scalar 到 CubeCore 的 accumulate 控制 |
| `src/test/scala/ascend/CubeCoreTest.scala` | 新增 L0C 累加单元测试 |
| `src/test/scala/ascend/IntegrationTest.scala` | 新增端到端 `MATMUL_ACC` 测试 |

## 功能性能计数器

优化前后运行相同测试：

```bash
sbt "testOnly ascend.IntegrationTest ascend.Pipeline3Test ascend.TripleBufferTest"
```

| 场景 | 指标 | 优化前 | 优化后 | 结论 |
|---|---:|---:|---:|---|
| 单核 8x8 MATMUL | totalCycles | 182 | 182 | 普通 MATMUL 周期不变 |
| 单核 8x8 MATMUL | cubeComputeCycles | 15 | 15 | SystolicArray 有效计算周期不变 |
| 单核 8x8 MATMUL | dmaTotalCycles | 72 | 72 | DMA 行为不变 |
| 单核 8x8 MATMUL | overlapCycles | 22 | 22 | DMA/Cube overlap 不变 |
| 单核 8x8 MATMUL | bubbleCycles | 2 | 2 | Scalar 等待行为不变 |
| Pipeline3 3 tile | totalCycles | 447 | 447 | LOAD/MATMUL overlap 不变 |
| Pipeline3 3 tile | cubeComputeCycles | 45 | 45 | 3 次 MATMUL 有效计算周期不变 |
| Pipeline3 3 tile | overlapCycles | 66 | 66 | overlap 不变 |
| TripleBuffer 4 tile | totalCycles | 602 | 602 | 三缓冲执行周期不变 |
| TripleBuffer 4 tile | cubeComputeCycles | 60 | 60 | 4 次 MATMUL 有效计算周期不变 |
| TripleBuffer 4 tile | overlapCycles | 88 | 88 | overlap 不变 |

解释：这次优化不是减少功能周期，而是提高同样 cycles 对应的可达频率。普通 MATMUL 没有新增可见 pipeline bubble；新增的 L0 输入快照在原 launch 周期内完成。

## Fmax 粗估

优化前后使用同一条命令，分别在改动前和改动后代码上运行：

```bash
REPORT_CSV=/tmp/npu_cube_fmax_before.csv scripts/estimate_fmax.sh CubeCore CubeUnit SystolicArray AiCore
REPORT_CSV=/tmp/npu_cube_fmax_after.csv scripts/estimate_fmax.sh CubeCore CubeUnit SystolicArray AiCore
```

环境：

```text
Yosys 0.33
OpenSTA 2.6.0
Liberty: sky130_fd_sc_hd slow corner, ss_100C_1v60
阶段: post-synthesis, pre-layout
```

| 模块 | 优化前 critical path | 优化前 Fmax | 优化后 critical path | 优化后 Fmax | 变化 |
|---|---:|---:|---:|---:|---:|
| `CubeCore` | 298.867 ns | 3.35 MHz | 136.527 ns | 7.32 MHz | +118.5% |
| `AiCore` | 287.161 ns | 3.48 MHz | 136.463 ns | 7.33 MHz | +110.6% |
| `SystolicArray` | 60.144 ns | 16.63 MHz | 59.591 ns | 16.78 MHz | 基本不变 |
| `CubeUnit` | 37.088 ns | 26.96 MHz | 50.547 ns | 19.78 MHz | 独立模块略降，但仍不是系统瓶颈 |

关键结论：

- `CubeCore` / `AiCore` 从约 3.4 MHz 提升到约 7.3 MHz，说明 L0→Cube 输入快照有效切断了原来的系统级长路径。
- `SystolicArray` 仍在约 16.8 MHz，后续若继续提频，下一刀应切 PE 阵列内部，而不是继续拆 Scalar/MTE 控制。
- `CubeUnit` 独立 Fmax 下降是因为它现在显式持有输入快照寄存器，独立综合的最长路径形态发生变化；系统级 `CubeCore` / `AiCore` 仍明显改善。

## Runtime 粗估

按 `runtime ~= cycles / Fmax`，使用 `AiCore` 粗估频率换算：

| 场景 | cycles | 优化前 3.48 MHz | 优化后 7.33 MHz |
|---|---:|---:|---:|
| 单核 8x8 MATMUL | 182 | 52.30 us | 24.83 us |
| Pipeline3 3 tile | 447 | 128.45 us | 60.98 us |
| TripleBuffer 4 tile | 602 | 172.99 us | 82.13 us |

注意：这是 pre-layout 粗估，不能等同最终芯片频率；但它能用于指导教学项目里的流水线切分方向。按当前结果，`CubeCore` 的系统级路径已经明显缩短，下一阶段应聚焦 `SystolicArray` / `PE` 内部乘加流水线。

## 验证结果

```text
sbt "testOnly ascend.CubeUnitTest ascend.CubeCoreTest ascend.IntegrationTest"
结果：3 suites / 6 tests passed

sbt "testOnly ascend.IntegrationTest ascend.Pipeline3Test ascend.TripleBufferTest"
结果：3 suites / 5 tests passed

REPORT_CSV=/tmp/npu_cube_fmax_after.csv scripts/estimate_fmax.sh CubeCore CubeUnit SystolicArray AiCore
结果：CubeCore 7.32 MHz, AiCore 7.33 MHz, SystolicArray 16.78 MHz
```
