# 英伟达 GPU 指令集 (9 条)

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

## GPU 特殊寄存器约定

真实 CUDA 程序通过 `threadIdx`、`blockIdx`、`blockDim`、`gridDim` 等 built-in variables 读取线程坐标。本项目的玩具 ISA 暂时用保留寄存器暴露 1D 坐标：

| 寄存器 | 含义 |
|--------|------|
| R12 | `threadIdx.x`，CTA 内线程号 |
| R13 | `warpIdxInCTA`，CTA 内 Warp 号 |
| R14 | `blockIdx.x` / CTA ID |
| R15 | 0，零基址寄存器 |

## 示例程序

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
