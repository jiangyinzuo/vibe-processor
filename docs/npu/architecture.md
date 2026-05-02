# 玩具版昇腾 NPU 架构文档

## 概述

玩具版昇腾 NPU：2×AiCore，显式 AIC/AIV 解耦 + MTE 多通路 + 分层 Local Memory。

使用 Chisel 7 (Scala) 编写，Verilator (via svsim) 仿真，ScalaTest 验证。

**[🔗 交互式架构图](../interactive/index.html)** - 可视化 NPU 架构，支持模块导航和数据流动画

---

## 1. 多核架构

![NPU 架构图](../diagrams/npu_architecture.svg)

- 2 个 AiCore 并行执行相同程序，处理不同数据分片（数据并行）
- 共享 L2 Buffer 和 InstrMem，每个核有私有 UB
- 每个核通过 `coreId` 自动偏移 L2 地址，访问各自的数据切片
- 每个 AiCore 内部由 Scalar dispatcher、AIC、AIV、MTE1、MTE2、MTE3 组成

---

## 2. 存储层次

![存储层次对比](../diagrams/storage_hierarchy.svg)

| 层级 | 类型 | 深度 | 延迟 | 共享范围 | 映射到真实昇腾 |
|------|------|------|------|----------|---------------|
| HBM | LatencyMem | 4096 | 10 cycles | 全局 | HBM/DDR |
| L2 Buffer | Mem | 2048 | 组合读 | 多核共享 | L2 Buffer |
| UB (per-core) | SyncReadMem (双端口) | 256 | 1 cycle | 单核私有 | Unified Buffer |
| L1 staging | Reg row | 8 elements | 0 | MTE1 私有 | L1 Buffer 简化切片 |
| L0A/L0B tile FIFO | Reg | 8×8×4 each | 0 | AIC 私有 | L0A/L0B |
| L0C | Reg | 8×8 | 0 | AIC 私有 | L0C |

**数据流**：`HBM → (预加载) → L2 → MTE2 → UB → MTE1 → L1 staging → L0A/L0B → AIC/Cube → L0C → MTE3 → UB → MTE2 → L2`

**关键特性：**
- **AIC/AIV 解耦**：AIC 独占 Cube 和 L0A/L0B/L0C，AIV 独立执行 VECADD/RELU
- **MTE 多通路**：MTE1 负责 UB→L1→L0A/L0B，MTE2 负责 L2↔UB，MTE3 负责 L0C→UB
- **UB 双端口**：Port A 由 MTE1/MTE3/AIV 仲裁，Port B 由 MTE2 独占
- **L0 tile FIFO**：L0A/L0B 各有 4 个 tile slot，支持本地 LOAD 与 MATMUL 重叠
- 详见 [DMA Overlap 优化文档](dma_overlap.md)

### 与真实昇腾存储层次的映射

| 真实昇腾 | 本项目 | 说明 |
|----------|--------|------|
| HBM/DDR (片外) | HBM (LatencyMem) | 片外高延迟存储 |
| L2 Buffer (多核共享) | L2 Buffer (Mem) | 多核共享片上存储 |
| L1 Buffer (单核私有) | MTE1 row staging + UB | L1 被简化为 MTE1 的行级 staging |
| L0A (Cube 激活) | AIC L0A tile FIFO | AIC 私有，按 tile slot 管理 |
| L0B (Cube 权重) | AIC L0B tile FIFO | AIC 私有，按 tile slot 管理 |
| L0C (Cube 结果) | AIC L0C | AIC 私有，MTE3 读回 UB |

---

## 3. 收缩阵列

![收缩阵列](../diagrams/systolic_array.svg)

Weight-Stationary 8×8 收缩阵列，计算 C = A × W (INT8 → INT32)。

- **绿色箭头**：激活值水平流动 (data →)
- **蓝色箭头**：部分和垂直累加 (psum ↓)
- PE 内部：`psumOut = psumIn + weightReg × dataIn`
- Skewed feeding：2N-1=15 个注入周期 + N=8 个排空周期
- **64 个 PE** 并行计算，每周期 64 次乘加操作

---

## 4. 指令集 (10 条)

详见 [isa.md](../isa.md#npu-指令集)

| 操作码 | 助记符 | 功能 | 行为 |
|--------|--------|------|------|
| 0x0 | NOP | 空操作 | 阻塞 |
| 0x1 | HALT | 停机 | 阻塞 |
| 0x2 | LOAD | UB → MTE1 → L1 → L0A/L0B | **非阻塞启动** |
| 0x3 | STORE | L0C → MTE3 → UB (N 行) | 阻塞 |
| 0x4 | MATMUL | AIC: C = A × W (8×8, INT8→INT32) | 阻塞等待 AIC |
| 0x5 | VECADD | AIV: 向量加法 (8路×32bit) | 阻塞等待 AIV |
| 0x6 | RELU | AIV: max(0, x) | 阻塞等待 AIV |
| 0x8 | DMA_LOAD | MTE2: L2 → UB (N 行, 含 coreId 偏移) | **非阻塞** |
| 0x9 | DMA_STORE | MTE2: UB → L2 (N 行, 含 coreId 偏移) | **非阻塞** |
| 0xA | DMA_WAIT | 等待所有 DMA 完成 | 阻塞 |

**关键特性：**
- LOAD 在 MTE1 空闲时启动后继续取指，AIC 内部等待完整 tile ready 后再计算
- DMA_LOAD/DMA_STORE 为非阻塞指令，支持 DMA-Compute Overlap
- DMA_WAIT 显式同步所有飞行中的 DMA 请求
- 详见 [DMA Overlap 优化文档](dma_overlap.md)

---

## 5. 性能计数器 (per-core × 18)

| 计数器 | 含义 |
|--------|------|
| totalCycles | start → halted 总周期 |
| instrNop/Halt/Load/Store/Matmul/Vecadd/Relu | 各类指令执行次数 |
| cubeTotalCycles / cubeComputeCycles | MATMUL 总耗时 / SA 有效计算周期 |
| bubbleCycles | Scalar 等待 Cube/Vector/DMA 的周期 |
| ubReads / ubWrites | UB 访存次数 |
| dmaLoadCount / dmaStoreCount / dmaTotalCycles | MTE2 DMA 统计 |
| overlapCycles | AIC 计算与 MTE1/MTE2 传输重叠周期 |

**派生指标**：
- Cube 利用率 = cubeCompute / cubeTotal
- DMA 占比 = dmaCycles / total

---

## 6. 性能数据

### 单核 MATMUL (8×8)

```
程序：DMA_LOAD×2 → LOAD×2 → MATMUL → STORE → DMA_STORE → HALT

性能：
  总周期：181
  DMA 周期：72
  重叠周期：22
```

### 2 核数据并行

```
Core 0: total=181, result OK
Core 1: total=181, result OK

吞吐量：2× 单核
```

---

## 7. 与真实昇腾的差异

| 维度 | 玩具 NPU | 真实昇腾 910 | 差距 |
|------|---------|-------------|------|
| **SystolicArray** | 8×8 (64 PE) | 16×16 (256 PE) | 4× |
| **存储层次** | UB + L1 staging + L0A/L0B/L0C + L2/HBM | 完整片上层次、bank、NoC | 仍是简化模型 |
| **MTE** | MTE1/MTE2/MTE3 三通路 | 更完整的 MTE/队列/同步机制 | 通路已拆分，调度仍简化 |
| **核心数** | 2 | 32 | 16× |
| **峰值性能** | 教学仿真级 | 256 TFLOPS 量级 | 不同目标 |

---

## 8. 测试

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

# 所有 NPU 测试
sbt "testOnly ascend.*"
```

---

## 9. 源代码

```
src/main/scala/ascend/
├── AscendParams.scala      # NPU 参数配置
├── PE.scala                # Processing Element
├── SystolicArray.scala     # 8×8 收缩阵列
├── CubeUnit.scala          # 矩阵计算单元
├── VectorUnit.scala        # 向量计算单元
├── AicCore.scala           # AIC：Cube + L0A/L0B/L0C
├── AivCore.scala           # AIV：Vector 执行核心
├── MteEngines.scala        # MTE1/MTE2/MTE3
├── ScalarUnit.scala        # 指令取指/译码/命令调度
├── DmaEngine.scala         # 旧 DMA 单元（保留源码，当前 AiCore 使用 MTE2）
├── Memory.scala            # UB + InstrMem
├── AiCore.scala            # AI 核心 (集成所有单元)
├── PerfCounters.scala      # 性能计数器
└── ToyAscendTop.scala      # 顶层 (2×AiCore + L2 + HBM)
```

---

## 相关文档

- [性能对比](performance_comparison.md) - NPU vs GPU 性能分析
- [架构差异](architecture_differences.md) - 玩具 vs 真实昇腾
- [指令集](../isa.md) - 详细指令说明
- [主文档](../README.md) - 返回文档索引
