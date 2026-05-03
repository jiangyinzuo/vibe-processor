# 指令集参考

## 昇腾 NPU 指令集 (10 条)

32 位固定宽度编码，4 位操作码。

### 编码格式

```
N-type (NOP, HALT, DMA_WAIT): [31:28] 操作码  [27:0] 保留
M-type (LOAD, STORE):         [31:28] 操作码  [27:26] 缓存选择  [19:4] UB地址
V-type (VECADD, RELU):        [31:28] 操作码  [27:22] 源1  [21:16] 源2  [15:10] 目标
C-type (MATMUL):              [31:28] 操作码  [27] 累加模式  [26:0] 保留
D-type (DMA_LOAD, DMA_STORE): [31:28] 操作码  [27:20] UB基地址  [19:4] L2基地址
```

### 指令表

| 操作码 | 助记符 | 功能 | 行为 |
|--------|--------|------|------|
| 0x0 | NOP | 空操作 | 阻塞 |
| 0x1 | HALT | 停机 | 阻塞 |
| 0x2 | LOAD | UB → 内部缓存 (N 行) | 阻塞 |
| 0x3 | STORE | 内部缓存 → UB (N 行) | 阻塞 |
| 0x4 | MATMUL | C = A × W 或 C += A × W (16×16, INT8→INT32) | 阻塞 |
| 0x5 | VECADD | 向量加法 (8路×32bit) | 阻塞 |
| 0x6 | RELU | max(0, x) | 阻塞 |
| 0x8 | DMA_LOAD | L2 → UB (N 行, 自动加 blockIdx 偏移) | **非阻塞** |
| 0x9 | DMA_STORE | UB → L2 (N 行, 自动加 blockIdx 偏移) | **非阻塞** |
| 0xA | DMA_WAIT | 等待所有 DMA 完成 | 阻塞 |

### 缓存选择 (LOAD/STORE)

| 值 | 符号 | 含义 |
|----|------|------|
| 0 | L0_A | 权重缓存 (Weight Buffer) |
| 1 | L0_B | 激活缓存 (Activation Buffer) |
| 2 | L0_C | 结果缓存 (Result Buffer) |

### MATMUL 累加模式

`MATMUL` 使用 bit 27 控制 L0C 写入方式：

| bit 27 | 行为 |
|--------|------|
| 0 | `L0C := L0A × L0B` |
| 1 | `L0C := L0C + L0A × L0B` |

这用于模拟真实矩阵计算里的 K 方向分块累加：多个 partial tile 可以先在 L0C 内累加，最后再通过 `STORE`/MTE3 写回 UB。

### SPMD block 地址模型

NPU 顶层支持 1D SPMD 启动模型：`blockDim` 指定逻辑 block 数，toy Control CPU 复用 `SpmdBlockScheduler` 将 `blockIdx` 分配给空闲物理 AiCore。DMA 指令中的 L2 地址会自动加上 `blockIdx * blockStride`：

```text
effectiveL2 = encodedL2Base + blockIdx * blockStride
```

默认 `blockStride = L2SliceSize = 1024`，兼容旧版按物理核切片的测试；也可以在构造 `ToyAscendTop` 时设置更小 stride 来让 2 个物理核执行更多逻辑 block。

### 示例程序

#### 顺序执行（无 Overlap）

```asm
DMA_LOAD  ub=0, l2=0       ; L2[blockIdx*blockStride + 0..7] → UB[0..7] (非阻塞)
DMA_LOAD  ub=8, l2=8       ; L2[blockIdx*blockStride + 8..15] → UB[8..15] (非阻塞)
DMA_WAIT                    ; 等待所有 DMA 完成
LOAD      L0_B, 0           ; UB[0..7] → 激活缓存
LOAD      L0_A, 8           ; UB[8..15] → 权重缓存
MATMUL                      ; C = A × W
STORE     L0_C, 16          ; 结果 → UB[16..23]
DMA_STORE ub=16, l2=16     ; UB[16..23] → L2[blockIdx*blockStride + 16..23] (非阻塞)
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

## 英伟达 GPU 指令集 (9 条)

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
| 0x8 | EXP | Rd = e^Rs1 |

### GPU 特殊寄存器约定

真实 CUDA 程序通过 `threadIdx`、`blockIdx`、`blockDim`、`gridDim` 等 built-in variables 读取线程坐标。本项目的玩具 ISA 暂时用保留寄存器暴露 1D 坐标：

| 寄存器 | 含义 |
|--------|------|
| R12 | `threadIdx.x`，CTA 内线程号 |
| R13 | `warpIdxInCTA`，CTA 内 Warp 号 |
| R14 | `blockIdx.x` / CTA ID |
| R15 | 0，零基址寄存器 |

### 示例程序

```asm
LD   R0, [R15 + 0]    ; 从 GlobalMem[0] 加载
LD   R1, [R15 + 1]    ; 从 GlobalMem[1] 加载
ADD  R2, R0, R1        ; R2 = R0 + R1 (4 lane 并行)
ST   [R15 + 2], R2     ; 存储结果
HALT
```

计算 1D 全局线程号：

```asm
LD   R0, [R15 + 0]    ; R0 = blockDim.x，例如 8
MUL  R1, R14, R0      ; R1 = blockIdx.x * blockDim.x
ADD  R2, R1, R12      ; R2 = global thread id
```

---

## 构建与测试

```bash
sbt test                          # 全部测试
sbt "runMain top.Elaborate"       # 生成 SystemVerilog
sbt "runMain top.Visualize"       # 渲染 d2 架构图
```
