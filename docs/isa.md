# 指令集参考

本项目包含两个玩具加速器：昇腾 NPU 和英伟达 GPU。

## 昇腾 NPU 指令集 (9 条)

32 位固定宽度编码，4 位操作码。

### 编码格式

```
N-type (NOP, HALT):
  [31:28] 操作码  [27:0] 保留

M-type (LOAD, STORE):
  [31:28] 操作码  [27:26] 缓存选择  [25:20] 保留  [19:4] UB地址  [3:0] 保留
  缓存选择: 00=L0_A(权重)  01=L0_B(激活)  10=L0_C(结果)  11=VEC

C-type (MATMUL):
  [31:28] 操作码  [27:0] 保留

V-type (VECADD, RELU):
  [31:28] 操作码  [27:22] 源1  [21:16] 源2  [15:10] 目标  [9:0] 保留

D-type (DMA_LOAD, DMA_STORE):
  [31:28] 操作码  [27:20] UB基地址  [19:4] HBM基地址  [3:0] 保留
```

### 指令表

| 操作码 | 助记符      | 功能                                 |
|--------|------------|--------------------------------------|
| 0x0    | NOP        | 空操作                               |
| 0x1    | HALT       | 停机                                 |
| 0x2    | LOAD       | UB → 内部缓存 (N 行)                 |
| 0x3    | STORE      | 内部缓存 → UB (N 行)                 |
| 0x4    | MATMUL     | C = A × W (4×4, INT8 → INT32)       |
| 0x5    | VECADD     | 向量加法 (4 路, 32 位)               |
| 0x6    | RELU       | 激活函数 max(0, x)                   |
| 0x8    | DMA_LOAD   | HBM → UB (N 行, 片外 → 片上)         |
| 0x9    | DMA_STORE  | UB → HBM (N 行, 片上 → 片外)         |

### 示例程序

```
DMA_LOAD  ub=0, hbm=0      ; HBM[0..3] → UB[0..3] (激活矩阵)
DMA_LOAD  ub=4, hbm=4      ; HBM[4..7] → UB[4..7] (权重矩阵)
LOAD      L0_B, 0           ; UB[0..3] → 激活缓存
LOAD      L0_A, 4           ; UB[4..7] → 权重缓存
MATMUL                       ; C = A × W
STORE     L0_C, 8           ; 结果 → UB[8..11]
DMA_STORE ub=8, hbm=100     ; UB[8..11] → HBM[100..103]
HALT
```

---

## 英伟达 GPU 指令集 (8 条)

32 位编码: `[31:28]操作码 [27:24]目标寄存器 [23:20]源1 [19:16]源2 [15:12]源3 [11:0]立即数`

所有 Warp 中的线程以 SIMT 方式并行执行同一条指令。

### 指令表

| 操作码 | 助记符 | 功能                              |
|--------|--------|-----------------------------------|
| 0x0    | NOP    | 空操作                            |
| 0x1    | HALT   | 停机 (当前 Warp)                   |
| 0x2    | LD     | Rd = GlobalMem[Rs1 + imm]         |
| 0x3    | ST     | GlobalMem[Rs1 + imm] = Rs2        |
| 0x4    | ADD    | Rd = Rs1 + Rs2                    |
| 0x5    | MUL    | Rd = Rs1 × Rs2                    |
| 0x6    | MAD    | Rd = Rs1 × Rs2 + Rs3 (乘累加)     |
| 0x7    | SHM    | SharedMem 操作 (imm[11]: 0=读, 1=写) |

### 示例程序

```
LD   R0, [R15 + 0]    ; 从 GlobalMem[0] 加载向量到 R0
LD   R1, [R15 + 1]    ; 从 GlobalMem[1] 加载向量到 R1
ADD  R2, R0, R1        ; R2 = R0 + R1 (4 条 lane 并行)
ST   [R15 + 2], R2     ; 存储结果到 GlobalMem[2]
HALT
```

---

## 构建与测试

```bash
sbt test                          # 全部 28 个测试
sbt "testOnly ascend.*"           # NPU 测试
sbt "testOnly gpu.*"              # GPU 测试
sbt "runMain top.Elaborate"       # 生成 SystemVerilog
```

## 可视化

```bash
sbt "runMain top.Visualize"       # Graphviz 架构图 → docs/diagrams/
bash tools/gen_schematic.sh       # RTL 原理图 → docs/schematics/
```
