# 指令集参考

## 昇腾 NPU 指令集 (10 条)

32 位固定宽度编码，4 位操作码。

### 编码格式

```
N-type (NOP, HALT, DMA_WAIT): [31:28] 操作码  [27:0] 保留
M-type (LOAD, STORE):         [31:28] 操作码  [27:26] 缓存选择  [19:4] UB地址
V-type (VECADD, RELU):        [31:28] 操作码  [27:22] 源1  [21:16] 源2  [15:10] 目标
C-type (MATMUL):              [31:28] 操作码  [27:0] 保留
D-type (DMA_LOAD, DMA_STORE): [31:28] 操作码  [27:20] UB基地址  [19:4] L2基地址
```

### 指令表

| 操作码 | 助记符 | 功能 | 行为 |
|--------|--------|------|------|
| 0x0 | NOP | 空操作 | 阻塞 |
| 0x1 | HALT | 停机 | 阻塞 |
| 0x2 | LOAD | UB → 内部缓存 (N 行) | 阻塞 |
| 0x3 | STORE | 内部缓存 → UB (N 行) | 阻塞 |
| 0x4 | MATMUL | C = A × W (8×8, INT8→INT32) | 阻塞 |
| 0x5 | VECADD | 向量加法 (8路×32bit) | 阻塞 |
| 0x6 | RELU | max(0, x) | 阻塞 |
| 0x8 | DMA_LOAD | L2 → UB (N 行, 自动加 coreId 偏移) | **非阻塞** |
| 0x9 | DMA_STORE | UB → L2 (N 行, 自动加 coreId 偏移) | **非阻塞** |
| 0xA | DMA_WAIT | 等待所有 DMA 完成 | 阻塞 |

### 缓存选择 (LOAD/STORE)

| 值 | 符号 | 含义 |
|----|------|------|
| 0 | L0_A | 权重缓存 (Weight Buffer) |
| 1 | L0_B | 激活缓存 (Activation Buffer) |
| 2 | L0_C | 结果缓存 (Result Buffer) |

### 示例程序

#### 顺序执行（无 Overlap）

```asm
DMA_LOAD  ub=0, l2=0       ; L2[coreId*1024 + 0..7] → UB[0..7] (非阻塞)
DMA_LOAD  ub=8, l2=8       ; L2[coreId*1024 + 8..15] → UB[8..15] (非阻塞)
DMA_WAIT                    ; 等待所有 DMA 完成
LOAD      L0_B, 0           ; UB[0..7] → 激活缓存
LOAD      L0_A, 8           ; UB[8..15] → 权重缓存
MATMUL                      ; C = A × W
STORE     L0_C, 16          ; 结果 → UB[16..23]
DMA_STORE ub=16, l2=16     ; UB[16..23] → L2[coreId*1024 + 16..23] (非阻塞)
DMA_WAIT                    ; 等待 DMA_STORE 完成
HALT
```

#### 流水线 Overlap

```asm
; Tile 0: 初始加载
DMA_LOAD  ub=0, l2=0       ; 加载 tile 0 activation
DMA_LOAD  ub=8, l2=8       ; 加载 tile 0 weight
DMA_WAIT
LOAD      L0_B, 0
LOAD      L0_A, 8

; Tile 1: 预取 + 计算重叠
DMA_LOAD  ub=0, l2=16      ; 预取 tile 1 (非阻塞)
DMA_LOAD  ub=8, l2=24      ; 预取 tile 1 (非阻塞)
MATMUL                      ; 计算 tile 0 (与 DMA 重叠!)
STORE     L0_C, 32
DMA_WAIT                    ; 等待 tile 1 数据
LOAD      L0_B, 0           ; 加载 tile 1
LOAD      L0_A, 8

; Tile 2: 继续重叠
DMA_LOAD  ub=0, l2=32      ; 预取 tile 2
DMA_LOAD  ub=8, l2=40
MATMUL                      ; 计算 tile 1 (与 DMA 重叠!)
STORE     L0_C, 40
DMA_WAIT
...
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
