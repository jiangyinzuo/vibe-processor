# 玩具版 AI 加速器架构文档

本项目包含两个教学用 AI 加速器的 RTL 实现：

- **玩具版昇腾 NPU** — 收缩阵列 + DMA + 片上/片外存储层次
- **玩具版英伟达 GPU** — SIMT 执行模型 + Warp 调度 + 可配置存储延迟

使用 Chisel 7 (Scala) 编写，Verilator (via svsim) 仿真，ScalaTest 验证。

---

## 一、昇腾 NPU

### 1.1 整体架构

```
┌─────────────────────────────────────────────────────────────────┐
│                         ToyAscendTop                            │
│                                                                 │
│  ┌───────────────────┐                                          │
│  │ HBM (片外)         │  LatencyMem, 4096×128b, 默认 10 周期延迟 │
│  └────────┬──────────┘                                          │
│           │ DMA_LOAD / DMA_STORE                                │
│  ┌────────▼──────────┐                                          │
│  │ DmaEngine          │  FSM, 逐行搬运 (N=4 行/次)              │
│  └────────┬──────────┘                                          │
│           │                                                     │
│  ┌────────▼──────────┐    ┌──────────────────┐                  │
│  │ UnifiedBuffer (UB) │    │ InstrMem          │                 │
│  │ 片上 SyncReadMem   │    │ 256×32b 组合读    │                 │
│  │ 1024×128b, 1 周期  │    └────────┬─────────┘                 │
│  └────────┬──────────┘             │                            │
│           │                        │                            │
│  ┌────────▼────────────────────────▼──────────────────────────┐ │
│  │                         AiCore                              │ │
│  │                                                             │ │
│  │  ScalarUnit ──── CubeUnit ──── VectorUnit                  │ │
│  │  (FSM 12态)      │             (VECADD/RELU)                │ │
│  │                   SystolicArray                              │ │
│  │                   └── PE[4][4]                               │ │
│  │                                                             │ │
│  │  PerfCounters (17 个计数器, 纯观测)                          │ │
│  └─────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────┘
```

### 1.2 存储层次

| 存储       | 类型        | 深度  | 字宽   | 读延迟     | 语义   |
|------------|-------------|-------|--------|------------|--------|
| HBM        | LatencyMem  | 4096  | 128 bit| 可配置 (默认10) | 片外 DRAM |
| UB         | SyncReadMem | 1024  | 128 bit| 1 周期     | 片上 SRAM |
| weightBuf/actBuf | RegInit | 4×4   | 8 bit  | 0 (组合读) | 寄存器级缓存 |
| cubeResult | RegInit     | 4×4   | 32 bit | 0 (组合读) | 计算结果暂存 |
| InstrMem   | Mem         | 256   | 32 bit | 0 (组合读) | 片上 I-Cache |

数据流：

```
HBM ──DMA──► UB ──LOAD──► weightBuf/actBuf ──► Cube ──► cubeResult ──STORE──► UB ──DMA──► HBM
```

#### 与真实昇腾存储层次的映射

真实昇腾有 5 级存储（HBM → L2 → L1 → L0A/L0B/L0C → UB），MTE（DMA 引擎）可在任意两级之间搬运。
本项目做了简化合并，映射关系如下：

```
真实昇腾                         本项目                    说明
────────────────────────────────────────────────────────────────────
HBM / DDR (片外)            ──►  HBM (LatencyMem)         片外高延迟存储
    │ MTE                            │ DmaEngine
L2 Buffer (多核共享)        ┐
                            ├──► UB (SyncReadMem)          合并为一级片上 SRAM
L1 Buffer (单核私有)        ┘
    │ MTE                            │ LOAD/STORE 指令
L0A (Cube 激活输入)         ──►  actBuf (Reg)              Scalar 内部寄存器
L0B (Cube 权重输入)         ──►  weightBuf (Reg)           Scalar 内部寄存器
L0C (Cube 结果输出)         ──►  cubeResult (Reg)          Cube 输出寄存器
UB  (Vector/Scalar 使用)    ──►  (合并到上面的 UB 中)
```

关键简化：
- L2 和 L1 合并为单一 UB，省去了多级 DMA 调度的复杂度
- `LOAD`/`STORE` 指令实质上模拟了 L1 → L0 的搬运过程
- 真实昇腾的 MTE 支持任意两级之间的异步搬运，本项目的 DmaEngine 仅支持 HBM ↔ UB

### 1.3 收缩阵列

Weight-Stationary 4×4 收缩阵列，计算 C = A × W (INT8 → INT32)。

```
  psum=0    psum=0    psum=0    psum=0
    ↓         ↓         ↓         ↓
  ┌─────┐  ┌─────┐  ┌─────┐  ┌─────┐
 →│W₀₀  │→│W₀₁  │→│W₀₂  │→│W₀₃  │→  actIn(0)
  └──┬──┘  └──┬──┘  └──┬──┘  └──┬──┘
     ↓        ↓        ↓        ↓
  ┌─────┐  ┌─────┐  ┌─────┐  ┌─────┐
 →│W₁₀  │→│W₁₁  │→│W₁₂  │→│W₁₃  │→  actIn(1)
  └──┬──┘  └──┬──┘  └──┬──┘  └──┬──┘
     ↓        ↓        ↓        ↓
  ┌─────┐  ┌─────┐  ┌─────┐  ┌─────┐
 →│W₂₀  │→│W₂₁  │→│W₂₂  │→│W₂₃  │→  actIn(2)
  └──┬──┘  └──┬──┘  └──┬──┘  └──┬──┘
     ↓        ↓        ↓        ↓
  ┌─────┐  ┌─────┐  ┌─────┐  ┌─────┐
 →│W₃₀  │→│W₃₁  │→│W₃₂  │→│W₃₃  │→  actIn(3)
  └──┬──┘  └──┬──┘  └──┬──┘  └──┬──┘
     ↓        ↓        ↓        ↓
  C[i][0]  C[i][1]  C[i][2]  C[i][3]
```

- 激活值水平流动 (→)，部分和垂直累加 (↓)
- PE 内部: `psumOut = psumIn + weightReg × dataIn`（寄存器输出）
- Skewed feeding: 2N-1=7 个注入周期 + N=4 个排空周期

### 1.4 ScalarUnit 执行流程

```
     ┌──────┐
     │ IDLE │
     └──┬───┘
  start │
        ▼
     ┌──────┐     ┌────────┐
 ┌──►│FETCH │────►│ DECODE │
 │   └──────┘     └───┬────┘
 │                    │
 │   ├── NOP ─────────────────────────► PC++ ──┐
 │   ├── HALT ────────────────────────► HALTED │
 │   ├── LOAD ──► LOAD0→LOAD1→LOAD2 ──────────┤  (N 行循环)
 │   ├── STORE ─► STORE0→STORE1 ───────────────┤  (N 行循环)
 │   ├── MATMUL ► 等待 cubeDone ───────────────┤
 │   ├── VECADD/RELU ► 等待 vecDone ───────────┤
 │   └── DMA_LOAD/STORE ► 等待 dmaDone ────────┤
 │                                              │
 └──────────────────────────────────────────────┘
```

### 1.5 指令集 (9 条)

| 操作码 | 助记符     | 编码格式                                      | 功能                           |
|--------|-----------|-----------------------------------------------|-------------------------------|
| 0x0    | NOP       | N-type                                        | 空操作                         |
| 0x1    | HALT      | N-type                                        | 停机                           |
| 0x2    | LOAD      | M-type: [27:26]buf [19:4]ub_addr              | UB → 内部缓存                  |
| 0x3    | STORE     | M-type: [27:26]buf [19:4]ub_addr              | 内部缓存 → UB                  |
| 0x4    | MATMUL    | C-type                                        | C = A × W (4×4, INT8→INT32)   |
| 0x5    | VECADD    | V-type: [27:22]src1 [21:16]src2 [15:10]dst    | 向量加法 (4路×32bit)           |
| 0x6    | RELU      | V-type: [27:22]src1 [15:10]dst                | max(0, x)                      |
| 0x8    | DMA_LOAD  | D-type: [27:20]ub_base [19:4]hbm_base         | HBM → UB (N 行)               |
| 0x9    | DMA_STORE | D-type: [27:20]ub_base [19:4]hbm_base         | UB → HBM (N 行)               |

### 1.6 性能计数器

| 计数器              | 触发条件                          | 含义                    |
|---------------------|-----------------------------------|------------------------|
| `totalCycles`       | start → halted 每周期 +1          | 总执行周期              |
| `instrNop/Halt/...` | 离开 sDecode 时按 opLat 分类      | 各类指令执行次数        |
| `cubeTotalCycles`   | cubeStart → cubeDone              | MATMUL 总耗时           |
| `cubeComputeCycles` | CubeUnit 在 sFeed 状态            | SA 有效计算周期         |
| `bubbleCycles`      | Scalar 等待 Cube/Vector/DMA       | 流水线气泡              |
| `ubReads/ubWrites`  | Scalar 的 UB 读/写                | UB 访存次数             |
| `dmaLoadCount`      | 离开 sDecode 时 opLat==DMA_LOAD   | DMA 加载次数            |
| `dmaStoreCount`     | 离开 sDecode 时 opLat==DMA_STORE  | DMA 存储次数            |
| `dmaTotalCycles`    | dmaStart → dmaDone                | DMA 总耗时              |

**派生指标**:
- Cube 利用率 = `cubeComputeCycles / cubeTotalCycles`
- DMA 占比 = `dmaTotalCycles / totalCycles`

### 1.7 示例：完整流水线性能数据

程序: `DMA_LOAD×2 → LOAD×2 → MATMUL → STORE → DMA_STORE → HALT` (HBM latency=5)

```
Total cycles:      139
DMA total cycles:  71  (51%)
Cube total:        16
Cube compute:      7   (利用率 43.8%)
```

---

## 二、英伟达 GPU

### 2.1 整体架构

```
┌─────────────────────────────────────────────────────────────┐
│                        ToyGpuTop                             │
│                                                              │
│  ┌──────────────┐    ┌─────────────────────────────────┐     │
│  │ InstrMem      │    │ GlobalMem (片外语义)             │     │
│  │ 256×32b       │    │ Mem, 1024×128b                  │     │
│  │ 共享, 组合读  │    │ 延迟在 Warp 内部建模 (memLatency)│     │
│  └───────┬──────┘    └───────────┬─────────────────────┘     │
│          │                       │                           │
│  ┌───────▼───────────────────────▼───────────────────────┐   │
│  │                         SM                             │   │
│  │                                                        │   │
│  │  WarpScheduler (Round-Robin)                           │   │
│  │       │                                                │   │
│  │  ┌────▼────┐ ┌────────┐ ┌────────┐ ┌────────┐         │   │
│  │  │ Warp 0  │ │ Warp 1 │ │ Warp 2 │ │ Warp 3 │         │   │
│  │  │ 4 lanes │ │ 4 lanes│ │ 4 lanes│ │ 4 lanes│         │   │
│  │  │ PC+RegF │ │ PC+RegF│ │ PC+RegF│ │ PC+RegF│         │   │
│  │  │ CudaCore│ │ CudaCore│ │ CudaCore│ │ CudaCore│       │   │
│  │  │ ×4      │ │ ×4     │ │ ×4     │ │ ×4     │         │   │
│  │  └─────────┘ └────────┘ └────────┘ └────────┘         │   │
│  │                                                        │   │
│  │  SharedMem (片上, SyncReadMem, 256×128b)               │   │
│  └────────────────────────────────────────────────────────┘   │
│                                                              │
│  GpuPerfCounters (4 个计数器)                                │
└──────────────────────────────────────────────────────────────┘
```

### 2.2 SIMT 执行模型

- 4 个 Warp，每个 Warp 4 条 lane（线程），共享一个 PC
- 所有 Warp 执行同一个程序（SPMD 模式）
- WarpScheduler: Round-Robin 调度，跳过 halted 和 sMemWait 的 Warp
- CudaCore: 单周期 ALU (ADD/MUL/MAD)，寄存器输出

### 2.3 存储层次

| 存储       | 类型        | 深度  | 字宽    | 读延迟           | 语义         |
|------------|-------------|-------|---------|------------------|-------------|
| GlobalMem  | Mem         | 1024  | 128 bit | Warp 内部计数器 (默认10) | 片外 DRAM   |
| SharedMem  | SyncReadMem | 256   | 128 bit | 1 周期           | 片上 SM 内   |
| InstrMem   | Mem         | 256   | 32 bit  | 0 (组合读)       | 片上 I-Cache |
| RegFile    | RegInit     | 16×4  | 32 bit  | 0 (组合读)       | 每 Warp 私有 |

GlobalMem 延迟建模：Warp 执行 LD 时，数据组合读出并锁存到 `memDataLat`，然后在 `sMemWait` 状态等待 `memLatency` 个周期。此期间 scheduler 可以调度其他 Warp（latency hiding）。

### 2.4 指令集 (8 条)

指令格式: `[31:28]op [27:24]rd [23:20]rs1 [19:16]rs2 [15:12]rs3 [11:0]imm12`

| 操作码 | 助记符 | 功能                          |
|--------|--------|-------------------------------|
| 0x0    | NOP    | 空操作                        |
| 0x1    | HALT   | 停机                          |
| 0x2    | LD     | Rd = GlobalMem[Rs1 + imm]     |
| 0x3    | ST     | GlobalMem[Rs1 + imm] = Rs2    |
| 0x4    | ADD    | Rd = Rs1 + Rs2                |
| 0x5    | MUL    | Rd = Rs1 × Rs2                |
| 0x6    | MAD    | Rd = Rs1 × Rs2 + Rs3          |
| 0x7    | SHM    | SharedMem 操作 (imm[11]=方向) |

### 2.5 性能数据

Vector ADD 程序 (`LD×2 → ADD → ST → HALT`)：

| 指标 | gmemLatency=1 | gmemLatency=10 |
|------|---------------|----------------|
| Total cycles | 14 | 28 |
| Active warp-cycles | 14 | 16 |
| Gmem reads | 8 | 8 |
| Gmem writes | 4 | 4 |

延迟从 1→10 时总周期翻倍，但 scheduler 有效调度其他 Warp 隐藏了部分延迟。

---

## 三、共享组件 (common 包)

| 模块 | 功能 |
|------|------|
| `Params` | 共享参数 (DataWidth=8, AccWidth=32) |
| `MAC` | 乘累加单元: out = a×b + c (寄存器输出) |
| `LatencyMem` | 可配置延迟存储, valid/ready 握手 + 直接访问端口 |
| `SramMem` | DualPortSram / SinglePortRom 封装 |

---

## 四、NPU vs GPU 对比

| 特性         | 玩具版昇腾 NPU              | 玩具版英伟达 GPU               |
|--------------|------------------------------|-------------------------------|
| 计算模型     | 收缩阵列 (矩阵乘法)          | SIMT (标量 ALU × 并行线程)     |
| 计算单元     | 4×4 PE 阵列, INT8→INT32     | 4 CudaCore × 4 Warp, INT32   |
| 调度         | 顺序 FSM                     | Round-Robin Warp Scheduler    |
| 片上存储     | UB (1024×128b)               | SharedMem (256×128b) + RegFile|
| 片外存储     | HBM (LatencyMem, 4096 深度)  | GlobalMem (Mem + 延迟计数器)   |
| 数据搬运     | DMA 引擎 (显式指令)           | LD/ST 指令 (Warp 内延迟等待)  |
| 指令数       | 9 条                         | 8 条                          |
| 延迟隐藏     | 无 (顺序执行)                 | Warp 切换                     |

---

## 五、构建与测试

```bash
sbt test                                # 全部 28 个测试
sbt "testOnly ascend.*"                 # NPU 测试 (17 cases)
sbt "testOnly gpu.*"                    # GPU 测试 (7 cases)
sbt "testOnly common.*"                 # 共享组件测试 (4 cases)
sbt "testOnly ascend.DmaTest"           # DMA 测试
sbt "testOnly ascend.PerfCounterTest"   # NPU 性能计数器测试
sbt "testOnly gpu.GpuIntegrationTest"   # GPU 集成测试 (含延迟对比)
sbt "runMain top.Elaborate"             # 生成 SystemVerilog
```

## 六、电路可视化

### 模块层次图 (Graphviz)

```bash
sbt "runMain top.Visualize"
```

输出 `docs/diagrams/{architecture,systolic_array}.svg`。依赖: `graphviz`

### RTL 原理图 (Yosys + netlistsvg)

```bash
sbt "runMain top.Elaborate"
bash tools/gen_schematic.sh
```

输出 `docs/schematics/{PE,VectorUnit,CubeUnit}.svg`。依赖: `yosys`, `netlistsvg`

---

## 七、项目结构

```
vibe-processor/
├── build.sbt                              Chisel 7.9.0 + ScalaTest
├── src/main/scala/
│   ├── common/                            共享基础组件
│   │   ├── Params.scala                   DataWidth=8, AccWidth=32
│   │   ├── MAC.scala                      乘累加单元
│   │   ├── LatencyMem.scala               可配置延迟存储 (HBM/DRAM 建模)
│   │   └── SramMem.scala                  SRAM 封装
│   ├── ascend/                            昇腾 NPU
│   │   ├── AscendParams.scala             NPU 参数 (含 HBM)
│   │   ├── PE.scala                       处理单元
│   │   ├── SystolicArray.scala            4×4 收缩阵列
│   │   ├── CubeUnit.scala                 Cube 单元
│   │   ├── VectorUnit.scala               向量单元
│   │   ├── ScalarUnit.scala               标量控制单元 (12 态 FSM)
│   │   ├── DmaEngine.scala                DMA 引擎 (HBM↔UB)
│   │   ├── Memory.scala                   UB + InstrMem
│   │   ├── AiCore.scala                   集成 + 性能计数器
│   │   ├── ToyAscendTop.scala             NPU 顶层
│   │   └── PerfCounters.scala             计数器 Bundle (17 个)
│   ├── gpu/                               英伟达 GPU
│   │   ├── GpuParams.scala                GPU 参数 + 操作码
│   │   ├── CudaCore.scala                 CUDA Core (ADD/MUL/MAD)
│   │   ├── Warp.scala                     Warp (4 线程 SIMT + 延迟模型)
│   │   ├── WarpScheduler.scala            Round-Robin 调度器
│   │   ├── SM.scala                       Streaming Multiprocessor
│   │   └── ToyGpuTop.scala               GPU 顶层 + 性能计数器
│   └── top/                               跨项目工具
│       ├── Elaborate.scala                Verilog 生成 (NPU + GPU)
│       └── Visualize.scala                Graphviz 架构图生成
├── src/test/scala/
│   ├── common/LatencyMemTest.scala        4 cases
│   ├── ascend/                            17 cases
│   │   ├── PETest / SystolicArrayTest / VectorUnitTest / CubeUnitTest
│   │   ├── IntegrationTest / PerfCounterTest
│   │   └── DmaTest
│   └── gpu/                               7 cases
│       ├── CudaCoreTest
│       └── GpuIntegrationTest
├── tools/gen_schematic.sh                 Yosys+netlistsvg 原理图
├── generated/                             生成的 SystemVerilog
│   ├── ascend/ + ascend/yosys/
│   └── gpu/ + gpu/yosys/
└── docs/
    ├── diagrams/                          Graphviz 层次图
    ├── schematics/                        RTL 原理图
    ├── isa.md                             指令集参考 (英文)
    └── architecture_zh.md                 架构文档 (本文件)
```
