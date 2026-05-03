# 玩具版昇腾 NPU 架构文档

## 概述

玩具版昇腾 NPU：显式 Control CPU + AI CPU 辅助执行部件 + SPMD block 调度 + 2×AiCore，显式 CubeCore/VectorCore 解耦 + MTE 多通路 + 分层 Local Memory。

使用 Chisel 7 (Scala) 编写，Verilator (via svsim) 仿真，ScalaTest 验证。

**[🔗 交互式架构图](../interactive/index.html)** - 可视化 NPU 架构，支持模块导航和数据流动画

---

## 1. 多核架构

![NPU 架构图](../diagrams/npu_architecture.svg)

- 1 个 toy Control CPU 作为 device-side 控制核，负责 SPMD logical block 调度
- 1 个 toy AI CPU 辅助执行部件，建模 device-side CPU 类任务，目前支持简单 L2 row task
- 2 个物理 AiCore 并行执行同一个 SPMD kernel，处理不同逻辑 block 的数据分片
- 顶层 `blockDim` 表示逻辑 block 数；`blockDim=0` 时默认等于物理 AiCore 数，兼容旧测试
- `ControlCpu` 内部复用 `SpmdBlockScheduler`，将 `blockIdx` 分配给空闲物理 AiCore；因此 `blockDim` 可以大于物理核数
- 共享 L2 Buffer 和 InstrMem，每个核有私有 UB
- 每个逻辑 block 通过 `blockIdx * blockStride` 自动偏移 L2 地址，访问各自的数据切片
- 每个 AiCore 内部由 Scalar dispatcher、CubeCore、VectorCore、MTE1、MTE2、MTE3 组成
- 命名上，`CubeCore` 对应真实昇腾分离模式中的 AIC，`VectorCore` 对应 AIV；本项目保留 `AiCore` 作为 toy AI Core group / wrapper。

真实昇腾 NPU 中，AI CPU 是 device 侧 ARM64 处理器，具备访问 device 侧内存资源的能力，通常作为 AI Core 的补充，承担非矩阵类、分支密集或控制复杂的计算。它不应简单等同于 SPMD scheduler；kernel/task 分发更接近 runtime、task scheduler、Control CPU/固件和硬件队列的职责。本项目因此把 SPMD block dispatch 放在 `ControlCpu`，把 `AiCpu` 建模为独立辅助执行部件。

参考资料：[华为云 NPU 虚拟化文档](https://support.huaweicloud.com/intl/en-us/usermanual-cce/cce_10_1009.html)把 AI Cores、AI CPUs 和 memory 作为 NPU 核心资源；[昇腾社区 Ascend C AI CPU 编程文档](https://www.hiascend.com/document/detail/zh/CANNCommunityEdition/850alpha002/opdevg/Ascendcopdevg/atlas_ascendc_10_00049.html)说明 AI CPU 是 device 侧 ARM64 处理器，适合非矩阵类和复杂分支计算。

当前 `ToyAscendTop` 已显式实例化 `AiCpu`，但顶层任务队列暂未暴露，命令输入先置空；`AiCpuTest` 会直接验证该辅助执行部件的 `FILL/COPY/ADD_IMM` 类 L2 row task。

---

## 2. SPMD 编程模型

真实 Ascend C 中，kernel 使用 `kernel<<<blockDim, l2ctrl, stream>>>(...)` 启动。`blockDim` 指定逻辑核/逻辑 block 数，每个执行实例可通过 `GetBlockIdx()` 获取自己的逻辑 ID，并据此计算 GM/L2 数据偏移。

本项目实现的是一个 1D SPMD 子集：

| 概念 | 本项目实现 |
|------|------------|
| `blockDim` | `ToyAscendTop.io.blockDim`，0 表示默认物理核数 |
| `blockIdx` | `ControlCpu` 分配给物理 AiCore 的运行时逻辑 ID |
| `GetBlockIdx()` | 暂不作为 scalar 指令暴露；由 DMA 地址路径自动使用 |
| 数据分片 | `effectiveL2 = instr.l2Base + blockIdx * blockStride` |
| 物理调度 | `ControlCpu` 复用 `SpmdBlockScheduler`，空闲 AiCore 自动领取下一个 block，直到完成 `blockDim` 个 block |

模块复用关系：

```text
ToyAscendTop
  └── ControlCpu
        └── SpmdBlockScheduler
              ├── coreLaunch(valid, blockIdx)
              ├── coreActive
              └── completedBlocks
```

这意味着同一段 NPU 程序可以在多个逻辑 block 上重复执行：

```asm
DMA_LOAD  ub=0,  l2=0      ; 实际 L2: blockIdx*blockStride + 0
DMA_LOAD  ub=8,  l2=8      ; 实际 L2: blockIdx*blockStride + 8
...
DMA_STORE ub=16, l2=16     ; 实际 L2: blockIdx*blockStride + 16
HALT                       ; 当前 block 完成，物理核可领取下一个 block
```

当前限制：

- 只实现 1D `blockIdx`。
- `GetBlockIdx()` 还不是 ISA 中可读的 scalar 寄存器/指令。
- `l2ctrl` 仍未建模；kernel launch 里可理解为保留参数。
- `blockStride` 是硬件构造参数；默认等于旧版 `L2SliceSize=1024`，测试中也可以设为更小值来运行更多逻辑 block。

---

## 3. 存储层次

![存储层次对比](../diagrams/storage_hierarchy.svg)

| 层级 | 类型 | 深度 | 延迟 | 共享范围 | 映射到真实昇腾 |
|------|------|------|------|----------|---------------|
| HBM | LatencyMem | 4096 | 10 cycles | 全局 | HBM/DDR |
| L2 Buffer | Mem | 2048 | 组合读 | 多核共享 | L2 Buffer |
| UB (per-core) | SyncReadMem (双端口) | 256 | 1 cycle | 单核私有 | Unified Buffer |
| L1 staging | Reg row | 8 elements | 0 | MTE1 私有 | L1 Buffer 简化切片 |
| L0A/L0B tile FIFO | Reg | 8×8×4 each | 0 | CubeCore 私有 | L0A/L0B |
| L0C | Reg | 8×8 | 0 | CubeCore 私有 | L0C |

**数据流**：`HBM → (预加载) → L2 → MTE2 → UB → MTE1 → L1 staging → L0A/L0B → CubeCore/Cube → L0C → MTE3 → UB → MTE2 → L2`

**关键特性：**
- **CubeCore/VectorCore 解耦**：CubeCore 独占 Cube 和 L0A/L0B/L0C，VectorCore 独立执行 VECADD/RELU
- **MTE 多通路**：MTE1 负责 UB→L1→L0A/L0B，MTE2 负责 L2↔UB，MTE3 负责 L0C→UB
- **UB 双端口**：Port A 由 MTE1/MTE3/VectorCore 仲裁，Port B 由 MTE2 独占
- **L0 tile FIFO**：L0A/L0B 各有 4 个 tile slot，支持本地 LOAD 与 MATMUL 重叠
- **CubeCore issue/launch 两级化**：MATMUL 先锁存 ready tile，再下一拍清 ready bit 并启动 CubeUnit，切断 tile ready 选择到 Cube 启动的同拍路径
- 详见 [DMA Overlap 优化文档](dma_overlap.md)

### 与真实昇腾存储层次的映射

| 真实昇腾 | 本项目 | 说明 |
|----------|--------|------|
| HBM/DDR (片外) | HBM (LatencyMem) | 片外高延迟存储 |
| L2 Buffer (多核共享) | L2 Buffer (Mem) | 多核共享片上存储 |
| L1 Buffer (单核私有) | MTE1 row staging + UB | L1 被简化为 MTE1 的行级 staging |
| L0A (Cube 激活) | CubeCore L0A tile FIFO | CubeCore 私有，按 tile slot 管理 |
| L0B (Cube 权重) | CubeCore L0B tile FIFO | CubeCore 私有，按 tile slot 管理 |
| L0C (Cube 结果) | CubeCore L0C | CubeCore 私有，MTE3 读回 UB |

---

## 4. 收缩阵列

![收缩阵列](../diagrams/systolic_array.svg)

Weight-Stationary 8×8 收缩阵列，计算 C = A × W (INT8 → INT32)。

- **绿色箭头**：激活值水平流动 (data →)
- **蓝色箭头**：部分和垂直累加 (psum ↓)
- PE 内部：`psumOut = psumIn + weightReg × dataIn`
- Skewed feeding：2N-1=15 个注入周期 + N=8 个排空周期
- **64 个 PE** 并行计算，每周期 64 次乘加操作

---

## 5. 指令集 (10 条)

详见 [isa.md](../isa.md#npu-指令集)

| 操作码 | 助记符 | 功能 | 行为 |
|--------|--------|------|------|
| 0x0 | NOP | 空操作 | 阻塞 |
| 0x1 | HALT | 停机 | 阻塞 |
| 0x2 | LOAD | UB → MTE1 → L1 → L0A/L0B | **非阻塞启动** |
| 0x3 | STORE | L0C → MTE3 → UB (N 行) | 阻塞 |
| 0x4 | MATMUL | CubeCore: C = A × W (8×8, INT8→INT32) | 阻塞等待 CubeCore |
| 0x5 | VECADD | VectorCore: 向量加法 (8路×32bit) | 阻塞等待 VectorCore |
| 0x6 | RELU | VectorCore: max(0, x) | 阻塞等待 VectorCore |
| 0x8 | DMA_LOAD | MTE2: L2 → UB (N 行, 含 blockIdx 偏移) | **非阻塞** |
| 0x9 | DMA_STORE | MTE2: UB → L2 (N 行, 含 blockIdx 偏移) | **非阻塞** |
| 0xA | DMA_WAIT | 等待所有 DMA 完成 | 阻塞 |

**关键特性：**
- LOAD 在 MTE1 空闲时启动后继续取指，CubeCore 内部等待完整 tile ready 后再计算
- DMA_LOAD/DMA_STORE 为非阻塞指令，支持 DMA-Compute Overlap
- DMA_WAIT 显式同步所有飞行中的 DMA 请求
- 详见 [DMA Overlap 优化文档](dma_overlap.md)

---

## 6. 性能计数器 (per-core × 21)

| 计数器 | 含义 |
|--------|------|
| totalCycles | start → halted 总周期 |
| blockStarts / blockCompletions / activeBlockCycles | SPMD block 启动、完成和活跃周期 |
| instrNop/Halt/Load/Store/Matmul/Vecadd/Relu | 各类指令执行次数 |
| cubeTotalCycles / cubeComputeCycles | MATMUL 总耗时 / SA 有效计算周期 |
| bubbleCycles | Scalar 等待 Cube/Vector/DMA 的周期 |
| ubReads / ubWrites | UB 访存次数 |
| dmaLoadCount / dmaStoreCount / dmaTotalCycles | MTE2 DMA 统计 |
| overlapCycles | CubeCore 计算与 MTE1/MTE2 传输重叠周期 |

**派生指标**：
- Cube 利用率 = cubeCompute / cubeTotal
- DMA 占比 = dmaCycles / total

---

## 7. 性能数据

### 单核 MATMUL (8×8)

```
程序：DMA_LOAD×2 → LOAD×2 → MATMUL → STORE → DMA_STORE → HALT

性能：
  总周期：182
  DMA 周期：72
  重叠周期：22
```

### 2 核数据并行

```
Core 0: total=182, result OK
Core 1: total=182, result OK

吞吐量：2× 单核
```

### SPMD blockDim > 物理核数

```
配置：2 个物理 AiCore，blockDim=4，blockStride=64
程序：同一段 MATMUL kernel
结果：4 个逻辑 block 全部完成
实测：367 cycles，blockStarts=4，blockCompletions=4
```

---

## 8. 与真实昇腾的差异

| 维度 | 玩具 NPU | 真实昇腾 910 | 差距 |
|------|---------|-------------|------|
| **SystolicArray** | 8×8 (64 PE) | 16×16 (256 PE) | 4× |
| **存储层次** | UB + L1 staging + L0A/L0B/L0C + L2/HBM | 完整片上层次、bank、NoC | 仍是简化模型 |
| **MTE** | MTE1/MTE2/MTE3 三通路 | 更完整的 MTE/队列/同步机制 | 通路已拆分，调度仍简化 |
| **Control CPU / scheduler** | 显式 `ControlCpu`，复用 `SpmdBlockScheduler` 负责 SPMD block dispatch | Runtime/TS/控制 CPU/固件/硬件队列共同参与 task 调度 | 调度简化为 1D block |
| **AI CPU** | 显式 `AiCpu` 辅助执行部件，支持 `FILL/COPY/ADD_IMM` 这类 L2 row task | Device 侧 ARM64 AI CPU，可运行更通用的控制/分支密集逻辑并访问 device 内存 | 当前不建模完整 ARM ISA/Linux 环境 |
| **核心数** | 2 | 32 | 16× |
| **SPMD block 调度** | 支持 1D blockDim/blockIdx，按空闲物理核领取 block | Ascend C runtime/TS 调度，支持更多资源约束 | 调度简化 |
| **峰值性能** | 教学仿真级 | 256 TFLOPS 量级 | 不同目标 |

---

## 9. 测试

```bash
# NPU 单元测试
sbt "testOnly ascend.PETest"
sbt "testOnly ascend.SystolicArrayTest"
sbt "testOnly ascend.CubeUnitTest"
sbt "testOnly ascend.VectorUnitTest"

# NPU 集成测试
sbt "testOnly ascend.IntegrationTest"
sbt "testOnly ascend.PerfCounterTest"

# NPU 多核测试
sbt "testOnly ascend.MultiCoreTest"

# SPMD blockDim/blockIdx 测试
sbt "testOnly ascend.SpmdTest"

# 所有 NPU 测试
sbt "testOnly ascend.*"
```

---

## 10. 源代码

```
src/main/scala/ascend/
├── AscendParams.scala      # NPU 参数配置
├── ControlCpu.scala        # Control CPU：复用 scheduler 调度 SPMD logical blocks
├── AiCpu.scala             # AI CPU 辅助执行部件：CPU 类 L2 row task
├── SpmdBlockScheduler.scala # 可复用 1D SPMD block scheduler
├── PE.scala                # Processing Element
├── SystolicArray.scala     # 8×8 收缩阵列
├── CubeUnit.scala          # 矩阵计算单元
├── VectorUnit.scala        # 向量计算单元
├── CubeCore.scala           # CubeCore：Cube + L0A/L0B/L0C
├── VectorCore.scala           # VectorCore：Vector 执行核心
├── MteEngines.scala        # MTE1/MTE2/MTE3
├── ScalarUnit.scala        # 指令取指/译码/命令调度
├── DmaEngine.scala         # 旧 DMA 单元（保留源码，当前 AiCore 使用 MTE2）
├── Memory.scala            # UB + InstrMem
├── AiCore.scala            # AI 核心 (集成所有单元)
├── PerfCounters.scala      # 性能计数器
└── ToyAscendTop.scala      # 顶层 (Control CPU + AiCore + L2 + HBM)
```

---

## 相关文档

- [性能对比](performance_comparison.md) - NPU vs GPU 性能分析
- [架构差异](architecture_differences.md) - 玩具 vs 真实昇腾
- [指令集](../isa.md) - 详细指令说明
- [主文档](../README.md) - 返回文档索引
