# 指令集参考

## 昇腾 NPU 指令集 (9 条)

32 位固定宽度编码，4 位操作码。

### 编码格式

```
N-type (NOP, HALT):     [31:28] 操作码  [27:0] 保留
M-type (LOAD, STORE):   [31:28] 操作码  [27:26] 缓存选择  [19:4] UB地址
V-type (VECADD, RELU):  [31:28] 操作码  [27:22] 源1  [21:16] 源2  [15:10] 目标
C-type (MATMUL):        [31:28] 操作码  [27:0] 保留
D-type (DMA):           [31:28] 操作码  [27:20] UB基地址  [19:4] L2基地址
```

### 指令表

| 操作码 | 助记符 | 功能 |
|--------|--------|------|
| 0x0 | NOP | 空操作 |
| 0x1 | HALT | 停机 |
| 0x2 | LOAD | UB → 内部缓存 (N 行) |
| 0x3 | STORE | 内部缓存 → UB (N 行) |
| 0x4 | MATMUL | C = A × W (4×4, INT8→INT32) |
| 0x5 | VECADD | 向量加法 (4路×32bit) |
| 0x6 | RELU | max(0, x) |
| 0x8 | DMA_LOAD | L2 → UB (N 行, 自动加 coreId 偏移) |
| 0x9 | DMA_STORE | UB → L2 (N 行, 自动加 coreId 偏移) |

### 示例程序

```asm
DMA_LOAD  ub=0, l2=0       ; L2[coreId*1024 + 0..3] → UB[0..3]
DMA_LOAD  ub=4, l2=4       ; L2[coreId*1024 + 4..7] → UB[4..7]
LOAD      L0_B, 0           ; UB[0..3] → 激活缓存
LOAD      L0_A, 4           ; UB[4..7] → 权重缓存
MATMUL                       ; C = A × W
STORE     L0_C, 8           ; 结果 → UB[8..11]
DMA_STORE ub=8, l2=8       ; UB[8..11] → L2[coreId*1024 + 8..11]
HALT
```

---

## 英伟达 GPU 指令集 (8 条)

32 位编码：`[31:28]操作码 [27:24]rd [23:20]rs1 [19:16]rs2 [15:12]rs3 [11:0]imm`

所有 Warp 中的线程以 SIMT 方式并行执行同一条指令。

| 操作码 | 助记符 | 功能 |
|--------|--------|------|
| 0x0 | NOP | 空操作 |
| 0x1 | HALT | 停机 (当前 Warp) |
| 0x2 | LD | Rd = GlobalMem[Rs1 + imm] |
| 0x3 | ST | GlobalMem[Rs1 + imm] = Rs2 |
| 0x4 | ADD | Rd = Rs1 + Rs2 |
| 0x5 | MUL | Rd = Rs1 × Rs2 |
| 0x6 | MAD | Rd = Rs1 × Rs2 + Rs3 |
| 0x7 | SHM | SharedMem 操作 (imm[11]: 0=读, 1=写) |

### 示例程序

```asm
LD   R0, [R15 + 0]    ; 从 GlobalMem[0] 加载
LD   R1, [R15 + 1]    ; 从 GlobalMem[1] 加载
ADD  R2, R0, R1        ; R2 = R0 + R1 (4 lane 并行)
ST   [R15 + 2], R2     ; 存储结果
HALT
```

---

## 构建与测试

```bash
sbt test                          # 全部 27 个测试
sbt "runMain top.Elaborate"       # 生成 SystemVerilog
sbt "runMain top.Visualize"       # 渲染 d2 架构图
```
