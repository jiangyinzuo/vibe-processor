# 玩具版昇腾 NPU 架构文档

## 项目简介

本项目是一个出于教学目的的简化版昇腾 (Ascend) NPU 实现，使用 Chisel 7 (Scala) 编写硬件描述，
通过 svsim + Verilator 进行仿真，ScalaTest 编写验证测试。可通过 CIRCT/firtool 生成 SystemVerilog。

项目展示了 AI 加速器的核心工作原理：
- 收缩阵列 (Systolic Array) 如何高效完成矩阵乘法
- Weight-Stationary 数据流的工作方式
- NPU 内部的三级执行单元协作（Scalar / Cube / Vector）
- 片上缓存层次与数据搬运机制

## 整体架构

```
┌─────────────────────────────────────────────────────────────────┐
│                       ToyAscendTop                              │
│                                                                 │
│  ┌──────────────────────┐    ┌──────────────────────────────┐   │
│  │     InstrMem          │    │      UnifiedBuffer           │   │
│  │   256 × 32-bit        │    │   1024 × 128-bit (SyncReadMem)│  │
│  │   Mem (组合读)        │    │                              │   │
│  │  loadEn ──►  [IMEM]   │    │  Port A ◄──► AI Core         │   │
│  │  loadAddr   addr ◄──┤    │  Port B ◄──► 外部接口(测试)  │   │
│  │  loadData   instr ──┤    │                              │   │
│  └───────────────────────┘    └──────────────────────────────┘   │
│              │                          ▲                        │
│              ▼                          │                        │
│  ┌──────────────────────────────────────┴─────────────────────┐  │
│  │                        AiCore                              │  │
│  │                                                            │  │
│  │  ┌────────────────────────────────────────────────────┐    │  │
│  │  │                  ScalarUnit                         │    │  │
│  │  │  ┌───────┐  ┌────────┐  ┌──────────────────────┐   │    │  │
│  │  │  │  PC   │─►│ DECODE │─►│    Execution FSM     │   │    │  │
│  │  │  └───────┘  └────────┘  │                      │   │    │  │
│  │  │                         │  FETCH → DECODE →     │   │    │  │
│  │  │                         │  LOAD / MATMUL /      │   │    │  │
│  │  │                         │  VECADD / HALT        │   │    │  │
│  │  │                         └──────────────────────┘   │    │  │
│  │  └──────┬──────────────┬──────────────────────────────┘    │  │
│  │         │              │                                   │  │
│  │    start/done     start/done                               │  │
│  │         │              │                                   │  │
│  │  ┌──────▼──────┐  ┌───▼────────────┐                      │  │
│  │  │  CubeUnit   │  │  VectorUnit    │                      │  │
│  │  │             │  │                │                      │  │
│  │  │  weightBuf  │  │  VECADD: a+b   │                      │  │
│  │  │  actBuf     │  │  RELU: max(0,x)│                      │  │
│  │  │      │      │  │                │                      │  │
│  │  │      ▼      │  │  单周期执行     │                      │  │
│  │  │  Systolic   │  └────────────────┘                      │  │
│  │  │  Array 4×4  │                                          │  │
│  │  │             │                                          │  │
│  │  │  resultReg  │                                          │  │
│  │  └─────────────┘                                          │  │
│  └────────────────────────────────────────────────────────────┘  │
│                                                                 │
│  start ──►                                        ──► halted    │
└─────────────────────────────────────────────────────────────────┘
```

## 收缩阵列详解

本设计采用 Weight-Stationary (权重固定) 数据流的 4×4 收缩阵列，
计算 C = A × W，其中 A 和 W 均为 4×4 矩阵。

### PE (处理单元) 内部结构

```
            dataIn (激活值)
               │
               ▼
         ┌───────────┐
         │           │
psumIn ──►  weight   ├──► psumOut = psumIn + weight × dataIn
         │  (固定)   │
         │           │
         └─────┬─────┘
               │
               ▼
            dataOut (传递给下一个 PE)
```

Chisel 实现（`PE.scala`）：
```scala
val weightReg = RegInit(0.S(dw.W))
when(io.weightLoad) { weightReg := io.weightIn }
io.dataOut := RegNext(io.dataIn, 0.S)
io.psumOut := RegNext(io.psumIn + weightReg * io.dataIn, 0.S)
```

### 4×4 阵列拓扑

```
  激活值从左侧注入 (水平流动 →)
  部分和从顶部累加 (垂直流动 ↓)

  psum=0    psum=0    psum=0    psum=0
    ↓         ↓         ↓         ↓
  ┌─────┐  ┌─────┐  ┌─────┐  ┌─────┐
 →│W₀₀  │→│W₀₁  │→│W₀₂  │→│W₀₃  │→  actIn(0): A[i][0]
  └──┬──┘  └──┬──┘  └──┬──┘  └──┬──┘
     ↓        ↓        ↓        ↓
  ┌─────┐  ┌─────┐  ┌─────┐  ┌─────┐
 →│W₁₀  │→│W₁₁  │→│W₁₂  │→│W₁₃  │→  actIn(1): A[i][1]
  └──┬──┘  └──┬──┘  └──┬──┘  └──┬──┘
     ↓        ↓        ↓        ↓
  ┌─────┐  ┌─────┐  ┌─────┐  ┌─────┐
 →│W₂₀  │→│W₂₁  │→│W₂₂  │→│W₂₃  │→  actIn(2): A[i][2]
  └──┬──┘  └──┬──┘  └──┬──┘  └──┬──┘
     ↓        ↓        ↓        ↓
  ┌─────┐  ┌─────┐  ┌─────┐  ┌─────┐
 →│W₃₀  │→│W₃₁  │→│W₃₂  │→│W₃₃  │→  actIn(3): A[i][3]
  └──┬──┘  └──┬──┘  └──┬──┘  └──┬──┘
     ↓        ↓        ↓        ↓
  C[i][0]  C[i][1]  C[i][2]  C[i][3]
```

Chisel 中用二维 `Array.tabulate` 生成 PE 阵列：
```scala
val pes = Array.tabulate(n, n)((_, _) => Module(new PE(dw, aw)))
for (k <- 0 until n; j <- 0 until n) {
  pes(k)(j).io.dataIn  := actH(k)(j)
  actH(k)(j + 1)       := pes(k)(j).io.dataOut
  pes(k)(j).io.psumIn  := psumV(k)(j)
  psumV(k + 1)(j)      := pes(k)(j).io.psumOut
}
```

### Skewed Feeding (斜向注入)

```
时钟周期:    0       1       2       3       4       5       6
─────────────────────────────────────────────────────────────────
Row 0 输入: A[0][0] A[1][0] A[2][0] A[3][0]   0       0       0
Row 1 输入:   0     A[0][1] A[1][1] A[2][1] A[3][1]   0       0
Row 2 输入:   0       0     A[0][2] A[1][2] A[2][2] A[3][2]   0
Row 3 输入:   0       0       0     A[0][3] A[1][3] A[2][3] A[3][3]
```

总共需要 2N-1 = 7 个注入周期，加上 N = 4 个排空周期。

### 结果捕获时序

C[i][j] 在绝对周期 `i + j + N` 时出现在底部：

```
周期:  4    5    6    7    8    9    10
      C00  C01  C02  C03
      C10  C11  C12  C13
      C20  C21  C22  C23
      C30  C31  C32  C33
```

## 模块层次

```
ToyAscendTop
├── InstrMem                     指令存储器 (Mem, 256 × 32-bit, 组合读)
├── UnifiedBuffer                统一缓存 (SyncReadMem, 1024 × 128-bit, 双端口)
└── AiCore
    ├── ScalarUnit               标量单元 (取指/译码/控制 FSM, 11 状态)
    ├── CubeUnit                 矩阵运算单元
    │   └── SystolicArray        4×4 收缩阵列
    │       └── PE [4][4]        16 个处理单元
    └── VectorUnit               向量运算单元 (VECADD / RELU)
```

## 指令集

7 条指令，32 位固定宽度编码，4 位操作码。

| 操作码 | 助记符  | 类型   | 功能                              |
|--------|---------|--------|-----------------------------------|
| 0x0    | NOP     | N-type | 空操作                            |
| 0x1    | HALT    | N-type | 停机                              |
| 0x2    | LOAD    | M-type | 从 Unified Buffer 加载到内部缓存  |
| 0x3    | STORE   | M-type | 从内部缓存写回 Unified Buffer     |
| 0x4    | MATMUL  | C-type | 4×4 矩阵乘法 C = A × W (INT8→INT32) |
| 0x5    | VECADD  | V-type | 向量加法 (4 路, 32 位)            |
| 0x6    | RELU    | V-type | ReLU 激活函数: max(0, x)          |

### 指令编码格式

```
N-type (NOP, HALT):
┌──────────┬────────────────────────────┐
│ [31:28]  │ [27:0]                     │
│ opcode   │ reserved                   │
└──────────┴────────────────────────────┘

M-type (LOAD, STORE):
┌──────────┬────────┬──────────┬─────────────────┬────────┐
│ [31:28]  │[27:26] │ [25:20]  │ [19:4]          │ [3:0]  │
│ opcode   │buf_sel │ reg_addr │ mem_addr         │ size   │
└──────────┴────────┴──────────┴─────────────────┴────────┘
  buf_sel: 00=L0_A(权重) 01=L0_B(激活) 10=L0_C(结果) 11=VEC

C-type (MATMUL):
┌──────────┬────────────────────────────┐
│ [31:28]  │ [27:0]                     │
│ opcode   │ reserved                   │
└──────────┴────────────────────────────┘

V-type (VECADD, RELU):
┌──────────┬────────┬────────┬────────┬──────────┐
│ [31:28]  │[27:22] │[21:16] │[15:10] │ [9:0]    │
│ opcode   │ src1   │ src2   │ dst    │ reserved │
└──────────┴────────┴────────┴────────┴──────────┘
```

## ScalarUnit 执行流程

ScalarUnit 是整个 AiCore 的控制中心，采用顺序执行的 FSM（11 个状态）：

```
         ┌──────┐
         │ IDLE │◄──── rst / 完成
         └──┬───┘
     start  │
            ▼
         ┌──────┐
    ┌───►│FETCH │ ◄─── PC 指向 InstrMem
    │    └──┬───┘
    │       │
    │       ▼
    │    ┌──────┐
    │    │DECODE│ ◄─── 译码操作码，锁存字段
    │    └──┬───┘
    │       │
    │       ├── NOP ──────────────────────► PC++ ──┐
    │       ├── HALT ─────────────────────► HALTED │
    │       │                                      │
    │       ├── LOAD ──► LOAD0 → LOAD1 → LOAD2 ───┤ (循环 N 行)
    │       │            发起UB读  保持addr  存入缓存│
    │       │                                      │
    │       ├── STORE ─► STORE0 → STORE1 ──────────┤ (循环 N 行)
    │       │            写入UB    下一行           │
    │       │                                      │
    │       ├── MATMUL ► MATMUL ───────────────────┤ (等待 cubeDone)
    │       │            启动CubeUnit               │
    │       │                                      │
    │       └── VEC ───► VEC ──────────────────────┤ (等待 vecDone)
    │                    启动VectorUnit             │
    │                                              │
    └──────────────────────────────────────────────┘
```

## 数据通路

一次完整的矩阵乘法执行流程：

```
1. LOAD L0_B (激活矩阵 A)
   UB[0] ──SyncReadMem──► ScalarUnit ──pack──► actBuf(0)
   UB[1] ──SyncReadMem──► ScalarUnit ──pack──► actBuf(1)
   UB[2] ──SyncReadMem──► ScalarUnit ──pack──► actBuf(2)
   UB[3] ──SyncReadMem──► ScalarUnit ──pack──► actBuf(3)

2. LOAD L0_A (权重矩阵 W)
   UB[4] ──SyncReadMem──► ScalarUnit ──pack──► weightBuf(0)
   ...

3. MATMUL
   weightBuf ──────────────────────────► CubeUnit ──► 加载权重到 PE
   actBuf ──► CubeUnit (skewing) ────► SystolicArray ──► 逐周期注入
                                                              │
   resultReg ◄──────────────────────────────────────── 底部捕获结果

4. STORE L0_C (结果矩阵 C)
   cubeResult(0) ──► ScalarUnit ──write──► UB[8]
   cubeResult(1) ──► ScalarUnit ──write──► UB[9]
   cubeResult(2) ──► ScalarUnit ──write──► UB[10]
   cubeResult(3) ──► ScalarUnit ──write──► UB[11]
```

## 关键设计参数

| 参数           | 值       | 说明                          |
|----------------|----------|-------------------------------|
| DataWidth      | 8 bit    | 输入数据精度 (INT8)           |
| AccWidth       | 32 bit   | 累加器精度 (INT32)            |
| ArraySize      | 4        | 收缩阵列维度 (4×4)           |
| UBDepth        | 1024     | Unified Buffer 深度           |
| UB 字宽        | 128 bit  | Vec(4, SInt(32.W))            |
| IMEMDepth      | 256      | 指令存储器深度                |
| InstrWidth     | 32 bit   | 指令宽度                      |
| MATMUL 延迟    | ~15 周期 | 含权重加载+注入+排空          |

## 与真实昇腾的对比

| 特性         | 本项目 (玩具版)              | 真实昇腾 310/910              |
|--------------|------------------------------|-------------------------------|
| Cube 单元    | 4×4 收缩阵列, INT8           | 16×16 收缩阵列, FP16/INT8    |
| Vector 单元  | 4 路 VECADD/RELU             | 256 路, 丰富的激活函数        |
| Scalar 单元  | 顺序 FSM                     | 完整 RISC 标量处理器          |
| 缓存层次     | UB (单级)                    | L0A/L0B/L0C/L1/UB 多级       |
| 指令集       | 7 条指令                     | 数百条指令, 支持流水线        |
| AI Core 数量 | 1 个                         | 2~32 个, 支持并行             |
| 数据流       | Weight-Stationary            | 多种数据流模式                |
| 实现语言     | Chisel 7 (Scala)             | 商业 EDA 工具链               |

## 构建与测试

```bash
sbt test                              # 运行全部 13 个测试用例
sbt "testOnly ascend.PETest"          # 仅测试 PE 单元
sbt "testOnly ascend.SystolicArrayTest" # 仅测试收缩阵列
sbt "testOnly ascend.VectorUnitTest"  # 仅测试 Vector 单元
sbt "testOnly ascend.CubeUnitTest"    # 仅测试 Cube 单元
sbt "testOnly ascend.IntegrationTest" # 运行集成测试 (完整程序执行)
sbt "runMain ascend.Elaborate"        # 生成 SystemVerilog 到 generated/
```

## 项目结构

```
vibe-processor/
├── build.sbt                              sbt 构建配置 (Chisel 7.9.0)
├── src/
│   ├── main/scala/ascend/
│   │   ├── AscendParams.scala             全局参数
│   │   ├── PE.scala                       处理单元 (MAC + 寄存器)
│   │   ├── SystolicArray.scala            4×4 Weight-Stationary 收缩阵列
│   │   ├── CubeUnit.scala                 Cube 单元 (封装收缩阵列 + skewing)
│   │   ├── VectorUnit.scala               向量单元 (VECADD / RELU)
│   │   ├── ScalarUnit.scala               标量单元 (取指/译码/控制 FSM)
│   │   ├── Memory.scala                   UnifiedBuffer + InstrMem
│   │   ├── AiCore.scala                   AI Core (集成三个执行单元)
│   │   ├── ToyAscendTop.scala             芯片顶层
│   │   └── Elaborate.scala                Verilog 生成入口
│   └── test/scala/ascend/
│       ├── PETest.scala                   PE 单元测试 (3 cases)
│       ├── SystolicArrayTest.scala        收缩阵列测试 (3 cases)
│       ├── VectorUnitTest.scala           Vector 单元测试 (3 cases)
│       ├── CubeUnitTest.scala             Cube 单元测试 (2 cases)
│       └── IntegrationTest.scala          集成测试 (2 cases, 完整程序执行)
├── generated/                             生成的 SystemVerilog (sbt runMain)
└── docs/
    ├── isa.md                             指令集参考 (英文)
    └── architecture_zh.md                 架构文档 (本文件)
```
