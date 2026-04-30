# 玩具版 AI 加速器架构文档

本项目包含两个教学用 AI 加速器的 RTL 实现：

- **玩具版昇腾 NPU** — 2×AiCore，收缩阵列 + DMA + 三级存储层次
- **玩具版英伟达 GPU** — 4×SM，SIMT 执行模型 + Warp 调度 + 可配置存储延迟

使用 Chisel 7 (Scala) 编写，Verilator (via svsim) 仿真，ScalaTest 验证。

---

## 一、昇腾 NPU

### 1.1 多核架构

![NPU 架构图](diagrams/npu_architecture.svg)

- 2 个 AiCore 并行执行相同程序，处理不同数据分片（数据并行）
- 共享 L2 Buffer 和 InstrMem，每个核有私有 UB
- 每个核通过 `coreId` 自动偏移 L2 地址，访问各自的数据切片

### 1.2 存储层次

![存储层次对比](diagrams/storage_hierarchy.svg)

| 层级 | 类型 | 深度 | 延迟 | 共享范围 | 映射到真实昇腾 |
|------|------|------|------|----------|---------------|
| HBM | LatencyMem | 4096 | 10 cycles | 全局 | HBM/DDR |
| L2 Buffer | Mem | 2048 | 组合读 | 多核共享 | L2 Buffer |
| UB (per-core) | SyncReadMem | 256 | 1 cycle | 单核私有 | L1 + UB |
| weightBuf/actBuf | Reg | 4×4 | 0 | 核内 | L0A/L0B |
| cubeResult | Reg | 4×4 | 0 | 核内 | L0C |

数据流：`HBM → (预加载) → L2 → DMA → UB → LOAD → L0 → Cube → L0C → STORE → UB → DMA → L2`

#### 与真实昇腾存储层次的映射

| 真实昇腾 | 本项目 | 说明 |
|----------|--------|------|
| HBM/DDR (片外) | HBM (LatencyMem) | 片外高延迟存储 |
| L2 Buffer (多核共享) | L2 Buffer (Mem) | 多核共享片上存储 |
| L1 Buffer (单核私有) | UB (SyncReadMem) | 合并 L1 和 UB |
| L0A (Cube 激活) | actBuf (Reg) | Scalar 内部寄存器 |
| L0B (Cube 权重) | weightBuf (Reg) | Scalar 内部寄存器 |
| L0C (Cube 结果) | cubeResult (Reg) | Cube 输出寄存器 |

### 1.3 收缩阵列

![收缩阵列](diagrams/systolic_array.svg)

Weight-Stationary 4×4 收缩阵列，计算 C = A × W (INT8 → INT32)。

- **绿色箭头**：激活值水平流动 (data →)
- **蓝色箭头**：部分和垂直累加 (psum ↓)
- PE 内部：`psumOut = psumIn + weightReg × dataIn`
- Skewed feeding：2N-1=7 个注入周期 + N=4 个排空周期

### 1.4 指令集 (9 条)

| 操作码 | 助记符 | 功能 |
|--------|--------|------|
| 0x0 | NOP | 空操作 |
| 0x1 | HALT | 停机 |
| 0x2 | LOAD | UB → 内部缓存 (N 行) |
| 0x3 | STORE | 内部缓存 → UB (N 行) |
| 0x4 | MATMUL | C = A × W (4×4, INT8→INT32) |
| 0x5 | VECADD | 向量加法 (4路×32bit) |
| 0x6 | RELU | max(0, x) |
| 0x8 | DMA_LOAD | L2 → UB (N 行, 含 coreId 偏移) |
| 0x9 | DMA_STORE | UB → L2 (N 行, 含 coreId 偏移) |

### 1.5 性能计数器 (per-core × 17)

| 计数器 | 含义 |
|--------|------|
| totalCycles | start → halted 总周期 |
| instrNop/Halt/Load/Store/Matmul/Vecadd/Relu | 各类指令执行次数 |
| cubeTotalCycles / cubeComputeCycles | MATMUL 总耗时 / SA 有效计算周期 |
| bubbleCycles | Scalar 等待 Cube/Vector/DMA 的周期 |
| ubReads / ubWrites | UB 访存次数 |
| dmaLoadCount / dmaStoreCount / dmaTotalCycles | DMA 统计 |

**派生指标**：Cube 利用率 = cubeCompute / cubeTotal，DMA 占比 = dmaCycles / total

### 1.6 示例性能数据

2 核 MATMUL (DMA_LOAD×2 → LOAD×2 → MATMUL → STORE → DMA_STORE → HALT)：

```
Core 0: total=107, dma=36, cube=16
Core 1: total=107, dma=36, cube=16
```

---

## 二、英伟达 GPU

### 2.1 多 SM 架构

![GPU 架构图](diagrams/gpu_architecture.svg)

- 4 个 SM 并行执行相同程序
- 每个 SM 内 4 个 Warp (每 Warp 4 线程)，Round-Robin 调度
- 共享 GlobalMem 和 InstrMem，每个 SM 有私有 SharedMem

### 2.2 SIMT 执行模型

- Warp = 4 条 lane（线程），共享 PC，SIMT 并行执行
- WarpScheduler：Round-Robin，跳过 halted 和 sMemWait 的 Warp
- CudaCore：单周期 ALU (ADD/MUL/MAD)，寄存器输出
- 存储延迟隐藏：Warp 在 LD 等待期间，scheduler 调度其他 Warp

### 2.3 存储层次

| 层级 | 类型 | 深度 | 延迟 | 共享范围 |
|------|------|------|------|----------|
| GlobalMem | Mem | 4096 | Warp 内计数器 (默认10) | 全局 (4 SM 共享) |
| SharedMem (per-SM) | SyncReadMem | 256 | 1 cycle | SM 内 |
| RegFile (per-Warp) | Reg | 16×4 | 0 | Warp 私有 |

### 2.4 指令集 (8 条)

格式：`[31:28]op [27:24]rd [23:20]rs1 [19:16]rs2 [15:12]rs3 [11:0]imm12`

| 操作码 | 助记符 | 功能 |
|--------|--------|------|
| 0x0 | NOP | 空操作 |
| 0x1 | HALT | 停机 (当前 Warp) |
| 0x2 | LD | Rd = GlobalMem[Rs1 + imm] |
| 0x3 | ST | GlobalMem[Rs1 + imm] = Rs2 |
| 0x4 | ADD | Rd = Rs1 + Rs2 |
| 0x5 | MUL | Rd = Rs1 × Rs2 |
| 0x6 | MAD | Rd = Rs1 × Rs2 + Rs3 |
| 0x7 | SHM | SharedMem 操作 |

### 2.5 性能数据 (per-SM)

Vector ADD (`LD×2 → ADD → ST → HALT`)，4 SM 并行：

| 指标 | gmemLatency=1 | gmemLatency=10 |
|------|---------------|----------------|
| Total cycles (per SM) | 20 | 34 |
| Active warp-cycles | 20 | 20 |

---

## 三、NPU vs GPU 对比

| 特性 | 昇腾 NPU (2核) | 英伟达 GPU (4SM) |
|------|----------------|------------------|
| 计算模型 | 收缩阵列 (矩阵乘法) | SIMT (标量 ALU × 并行线程) |
| 计算单元 | 2 × (4×4 PE), INT8→INT32 | 4SM × 4Warp × 4Core, INT32 |
| 调度 | 顺序 FSM (per-core) | Round-Robin Warp Scheduler |
| 片上存储 | L2(共享) + UB(私有) | SharedMem(per-SM) + RegFile |
| 片外存储 | HBM (LatencyMem, 延迟=10) | GlobalMem (Warp 内延迟=10) |
| 数据搬运 | DMA 引擎 (显式指令) | LD/ST (Warp stall + 切换隐藏) |
| 延迟隐藏 | 无 (顺序执行) | Warp 切换 |
| 指令数 | 9 条 | 8 条 |
| 多核协同 | 数据并行 (coreId 偏移) | 数据并行 (共享 GlobalMem) |

---

## 四、构建与测试

```bash
sbt test                                # 全部 27 个测试
sbt "testOnly ascend.*"                 # NPU 测试 (16 cases)
sbt "testOnly gpu.*"                    # GPU 测试 (7 cases)
sbt "testOnly common.*"                 # 共享组件测试 (4 cases)
sbt "testOnly ascend.MultiCoreTest"     # NPU 2核数据并行
sbt "testOnly gpu.GpuIntegrationTest"   # GPU 4SM 集成测试
sbt "runMain top.Elaborate"             # 生成 SystemVerilog
```

## 五、电路可视化

架构图使用 [d2](https://d2lang.com) 绘制：

```bash
sbt "runMain top.Visualize"             # 渲染所有 d2 → SVG
# 或单独渲染:
d2 docs/diagrams/npu_architecture.d2 docs/diagrams/npu_architecture.svg
d2 docs/diagrams/gpu_architecture.d2 docs/diagrams/gpu_architecture.svg
d2 docs/diagrams/systolic_array.d2 docs/diagrams/systolic_array.svg
d2 docs/diagrams/storage_hierarchy.d2 docs/diagrams/storage_hierarchy.svg
```

RTL 原理图（Yosys + netlistsvg）：

```bash
sbt "runMain top.Elaborate"
bash tools/gen_schematic.sh
```

依赖：`d2`、`graphviz`（dot 已不再需要）、`yosys`、`netlistsvg`

---

## 六、项目结构

```
vibe-processor/
├── build.sbt                              Chisel 7.9.0 + ScalaTest
├── src/main/scala/
│   ├── common/                            共享组件
│   │   ├── Params.scala                   DataWidth=8, AccWidth=32
│   │   ├── MAC.scala                      乘累加单元
│   │   ├── LatencyMem.scala               可配置延迟存储 (片外建模)
│   │   └── SramMem.scala                  SRAM 封装
│   ├── ascend/                            昇腾 NPU (2×AiCore)
│   │   ├── AscendParams.scala             NPU 参数 (NumCores=2, L2, HBM)
│   │   ├── PE.scala → SystolicArray.scala → CubeUnit.scala
│   │   ├── VectorUnit.scala
│   │   ├── ScalarUnit.scala               12 态 FSM (含 DMA 指令)
│   │   ├── DmaEngine.scala                L2↔UB 搬运 (内联到 AiCore)
│   │   ├── Memory.scala                   UB + InstrMem
│   │   ├── AiCore.scala                   集成 + DMA + PerfCounters
│   │   ├── ToyAscendTop.scala             多核顶层 (2×AiCore + L2 + HBM)
│   │   └── PerfCounters.scala             17 个计数器
│   ├── gpu/                               英伟达 GPU (4×SM)
│   │   ├── GpuParams.scala                GPU 参数 (NumSMs=4)
│   │   ├── CudaCore.scala                 CUDA Core (ADD/MUL/MAD)
│   │   ├── Warp.scala                     4 线程 SIMT + 延迟模型
│   │   ├── WarpScheduler.scala            Round-Robin
│   │   ├── SM.scala                       Streaming Multiprocessor
│   │   └── ToyGpuTop.scala               多SM顶层 (4×SM + 共享 GlobalMem)
│   └── top/
│       ├── Elaborate.scala                Verilog 生成 (NPU + GPU)
│       └── Visualize.scala                d2 → SVG 渲染
├── src/test/scala/
│   ├── common/LatencyMemTest.scala        4 cases
│   ├── ascend/                            16 cases
│   │   ├── PETest / SystolicArrayTest / VectorUnitTest / CubeUnitTest
│   │   ├── IntegrationTest / PerfCounterTest
│   │   └── MultiCoreTest (2核数据并行验证)
│   └── gpu/                               7 cases
│       ├── CudaCoreTest
│       └── GpuIntegrationTest (4SM 并行验证)
├── tools/gen_schematic.sh                 Yosys+netlistsvg
├── docs/
│   ├── diagrams/*.d2 → *.svg              d2 架构图
│   ├── schematics/                        RTL 原理图
│   ├── isa.md                             指令集参考
│   └── architecture_zh.md                 架构文档 (本文件)
└── generated/                             SystemVerilog 输出
```
