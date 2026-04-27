# 玩具版昇腾 NPU 架构文档

## 项目简介

本项目是一个出于教学目的的简化版昇腾 (Ascend) NPU 实现，使用 SystemVerilog 编写 RTL，
Verilator 进行仿真，cocotb/pytest 编写验证测试。

项目展示了 AI 加速器的核心工作原理：
- 收缩阵列 (Systolic Array) 如何高效完成矩阵乘法
- Weight-Stationary 数据流的工作方式
- NPU 内部的三级执行单元协作（Scalar / Cube / Vector）
- 片上缓存层次与数据搬运机制

## 整体架构

```
┌─────────────────────────────────────────────────────────────────┐
│                       toy_ascend_top                            │
│                                                                 │
│  ┌──────────────────────┐    ┌──────────────────────────────┐   │
│  │   Instruction Memory  │    │      Unified Buffer          │   │
│  │   256 × 32-bit        │    │   1024 × 128-bit 双端口 SRAM │   │
│  │                       │    │                              │   │
│  │  load_en ──►  [IMEM]  │    │  Port A ◄──► AI Core         │   │
│  │  load_addr    addr ◄──┤    │  Port B ◄──► 外部接口(测试)  │   │
│  │  load_data    instr ──┤    │                              │   │
│  └───────────────────────┘    └──────────────────────────────┘   │
│              │                          ▲                        │
│              ▼                          │                        │
│  ┌──────────────────────────────────────┴─────────────────────┐  │
│  │                        AI Core                             │  │
│  │                                                            │  │
│  │  ┌────────────────────────────────────────────────────┐    │  │
│  │  │                  Scalar Unit                        │    │  │
│  │  │  ┌───────┐  ┌────────┐  ┌──────────────────────┐   │    │  │
│  │  │  │  PC   │─►│ DECODE │─►│    Execution FSM     │   │    │  │
│  │  │  └───────┘  └────────┘  │                      │   │    │  │
│  │  │                         │  FETCH → DECODE →     │   │    │  │
│  │  │                         │  EXEC_LOAD / MATMUL / │   │    │  │
│  │  │                         │  VECADD / HALT        │   │    │  │
│  │  │                         └──────────────────────┘   │    │  │
│  │  └──────┬──────────────┬──────────────────────────────┘    │  │
│  │         │              │                                   │  │
│  │    start/done     start/done                               │  │
│  │         │              │                                   │  │
│  │  ┌──────▼──────┐  ┌───▼────────────┐                      │  │
│  │  │  Cube Unit  │  │  Vector Unit   │                      │  │
│  │  │             │  │                │                      │  │
│  │  │  weight_buf │  │  VECADD: a+b   │                      │  │
│  │  │  act_buf    │  │  RELU: max(0,x)│                      │  │
│  │  │      │      │  │                │                      │  │
│  │  │      ▼      │  │  单周期执行     │                      │  │
│  │  │  Systolic   │  └────────────────┘                      │  │
│  │  │  Array 4×4  │                                          │  │
│  │  │             │                                          │  │
│  │  │  result_buf │                                          │  │
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
            data_in (激活值)
               │
               ▼
         ┌───────────┐
         │           │
psum_in ─►  weight   ├─► psum_out = psum_in + weight × data_in
         │  (固定)   │
         │           │
         └─────┬─────┘
               │
               ▼
            data_out (传递给下一个 PE)
```

每个 PE 包含：
- 一个权重寄存器 `weight_reg`（加载后固定不变）
- 一个 MAC 运算：`psum_out = psum_in + weight_reg × data_in`
- 数据直通：`data_out = data_in`（延迟 1 个时钟周期）

### 4×4 阵列拓扑

```
  激活值从左侧注入 (水平流动 →)
  部分和从顶部累加 (垂直流动 ↓)

  psum=0    psum=0    psum=0    psum=0
    ↓         ↓         ↓         ↓
  ┌─────┐  ┌─────┐  ┌─────┐  ┌─────┐
 →│W₀₀  │→│W₀₁  │→│W₀₂  │→│W₀₃  │→  act_in[0]: A[i][0]
  └──┬──┘  └──┬──┘  └──┬──┘  └──┬──┘
     ↓        ↓        ↓        ↓
  ┌─────┐  ┌─────┐  ┌─────┐  ┌─────┐
 →│W₁₀  │→│W₁₁  │→│W₁₂  │→│W₁₃  │→  act_in[1]: A[i][1]
  └──┬──┘  └──┬──┘  └──┬──┘  └──┬──┘
     ↓        ↓        ↓        ↓
  ┌─────┐  ┌─────┐  ┌─────┐  ┌─────┐
 →│W₂₀  │→│W₂₁  │→│W₂₂  │→│W₂₃  │→  act_in[2]: A[i][2]
  └──┬──┘  └──┬──┘  └──┬──┘  └──┬──┘
     ↓        ↓        ↓        ↓
  ┌─────┐  ┌─────┐  ┌─────┐  ┌─────┐
 →│W₃₀  │→│W₃₁  │→│W₃₂  │→│W₃₃  │→  act_in[3]: A[i][3]
  └──┬──┘  └──┬──┘  └──┬──┘  └──┬──┘
     ↓        ↓        ↓        ↓
  C[i][0]  C[i][1]  C[i][2]  C[i][3]
```

计算原理：
- PE[k][j] 固定存储权重 W[k][j]
- 激活值 A[i][k] 从第 k 行左侧注入，向右传递
- 部分和从顶部（初始为 0）向下累加
- 底部输出 psum_v[4][j] = Σ_k A[i][k] × W[k][j] = C[i][j]

### Skewed Feeding (斜向注入)

为了让所有对 C[i][j] 有贡献的乘积在正确的时刻到达对应 PE，
激活值需要按行错开注入（skewing）：

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

由于 PE 的流水线延迟，C[i][j] 在绝对周期 `i + j + N` 时出现在底部：

```
周期:  4    5    6    7    8    9    10
      C00  C01  C02  C03
      C10  C11  C12  C13
      C20  C21  C22  C23
      C30  C31  C32  C33
```

## 模块层次

```
toy_ascend_top
├── instr_mem                    指令存储器 (256 × 32-bit)
├── unified_buffer               统一缓存 (1024 × 128-bit, 双端口)
└── ai_core
    ├── scalar_unit              标量单元 (取指/译码/控制 FSM)
    ├── cube_unit                矩阵运算单元
    │   └── systolic_array       4×4 收缩阵列
    │       └── pe [4][4]        16 个处理单元
    └── vector_unit              向量运算单元 (VECADD / RELU)
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
│ opcode   │buf_sel │ reg_addr │ mem_addr        │ size   │
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

## Scalar Unit 执行流程

Scalar Unit 是整个 AI Core 的控制中心，采用顺序执行的 FSM：

```
         ┌──────┐
         │ IDLE │◄──── rst_n / 完成
         └──┬───┘
     start  │
            ▼
         ┌──────┐
    ┌───►│FETCH │ ◄─── PC 指向指令存储器
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
    │       ├── LOAD ──► LOAD_0 → LOAD_1 → LOAD_2 ┤ (循环 N 行)
    │       │            发起UB读  等待数据  存入缓存│
    │       │                                      │
    │       ├── STORE ─► STORE_0 → STORE_1 ────────┤ (循环 N 行)
    │       │            写入UB    下一行           │
    │       │                                      │
    │       ├── MATMUL ► EXEC_MATMUL ──────────────┤ (等待 cube_done)
    │       │            启动Cube单元               │
    │       │                                      │
    │       └── VEC ───► EXEC_VEC ─────────────────┤ (等待 vec_done)
    │                    启动Vector单元             │
    │                                              │
    └──────────────────────────────────────────────┘
```

## 数据通路

一次完整的矩阵乘法执行流程：

```
1. LOAD L0_B (激活矩阵 A)
   UB[0] ──read──► Scalar Unit ──pack──► act_buf[row 0]
   UB[1] ──read──► Scalar Unit ──pack──► act_buf[row 1]
   UB[2] ──read──► Scalar Unit ──pack──► act_buf[row 2]
   UB[3] ──read──► Scalar Unit ──pack──► act_buf[row 3]

2. LOAD L0_A (权重矩阵 W)
   UB[4] ──read──► Scalar Unit ──pack──► weight_buf[row 0]
   UB[5] ──read──► Scalar Unit ──pack──► weight_buf[row 1]
   UB[6] ──read──► Scalar Unit ──pack──► weight_buf[row 2]
   UB[7] ──read──► Scalar Unit ──pack──► weight_buf[row 3]

3. MATMUL
   weight_buf ──────────────────────────► Cube Unit ──► 加载权重到 PE
   act_buf ──► Cube Unit (skewing) ────► Systolic Array ──► 逐周期注入
                                                              │
   result_buf ◄──────────────────────────────────────── 底部捕获结果

4. STORE L0_C (结果矩阵 C)
   cube_result[row 0] ──► Scalar Unit ──write──► UB[8]
   cube_result[row 1] ──► Scalar Unit ──write──► UB[9]
   cube_result[row 2] ──► Scalar Unit ──write──► UB[10]
   cube_result[row 3] ──► Scalar Unit ──write──► UB[11]
```

## 关键设计参数

| 参数           | 值       | 说明                          |
|----------------|----------|-------------------------------|
| DATA_WIDTH     | 8 bit    | 输入数据精度 (INT8)           |
| ACC_WIDTH      | 32 bit   | 累加器精度 (INT32)            |
| ARRAY_SIZE     | 4        | 收缩阵列维度 (4×4)           |
| UB_DEPTH       | 1024     | Unified Buffer 深度           |
| UB 字宽        | 128 bit  | 每地址存储 4 个 INT32 值      |
| IMEM_DEPTH     | 256      | 指令存储器深度                |
| INSTR_WIDTH    | 32 bit   | 指令宽度                      |
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
| 时钟频率     | 仿真                         | ~1 GHz                        |

## 示例程序

```asm
; 计算 C = A × W
; UB 布局: A 在地址 0-3, W 在地址 4-7, 结果写入 8-11

LOAD  L0_B, 0, 0     ; 从 UB[0..3] 加载激活矩阵 A
LOAD  L0_A, 0, 4     ; 从 UB[4..7] 加载权重矩阵 W
MATMUL                ; 执行矩阵乘法 C = A × W
STORE L0_C, 0, 8     ; 将结果 C 存回 UB[8..11]
HALT                  ; 停机
```

## 构建与测试

```bash
make test          # 运行全部 13 个测试用例
make test_pe       # 仅测试 PE 单元
make test_systolic # 仅测试收缩阵列
make test_vector   # 仅测试 Vector 单元
make test_cube     # 仅测试 Cube 单元
make test_integration  # 运行集成测试 (完整程序执行)
make clean         # 清理构建产物
```

## 项目结构

```
vibe-processor/
├── Makefile                         顶层构建脚本
├── rtl/
│   ├── pkg/ascend_pkg.sv            全局参数与类型定义
│   ├── cube/
│   │   ├── pe.sv                    处理单元 (MAC + 寄存器)
│   │   └── systolic_array.sv        4×4 Weight-Stationary 收缩阵列
│   ├── memory/
│   │   ├── l0_buffer.sv             L0 缓存 (单端口 SRAM)
│   │   ├── unified_buffer.sv        统一缓存 (双端口 SRAM)
│   │   └── instr_mem.sv             指令存储器
│   ├── core/
│   │   ├── scalar_unit.sv           标量单元 (取指/译码/控制)
│   │   ├── cube_unit.sv             Cube 单元 (封装收缩阵列)
│   │   ├── vector_unit.sv           向量单元 (VECADD/RELU)
│   │   └── ai_core.sv              AI Core (集成三个执行单元)
│   └── top/
│       └── toy_ascend_top.sv        芯片顶层
├── tb/
│   ├── test_pe/                     PE 单元测试 (3 cases)
│   ├── test_systolic/               收缩阵列测试 (3 cases, numpy 验证)
│   ├── test_vector/                 Vector 单元测试 (3 cases)
│   ├── test_cube/                   Cube 单元测试 (2 cases)
│   └── test_integration/            集成测试 (2 cases, 完整程序执行)
├── tools/
│   └── asm.py                       简易汇编器
├── programs/
│   └── matmul_simple.asm            示例程序
└── docs/
    ├── isa.md                       指令集参考 (英文)
    └── architecture_zh.md           架构文档 (本文件)
```
