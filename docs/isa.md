# Toy Ascend NPU - Instruction Set Architecture

## Overview

7 instructions, 32-bit fixed-width encoding, 4-bit opcode.

## Instruction Encoding

### N-type (NOP, HALT)
```
[31:28] opcode  [27:0] reserved (0)
```

### M-type (LOAD, STORE)
```
[31:28] opcode  [27:26] buf_sel  [25:20] reg_addr  [19:4] mem_addr  [3:0] size
```
- `buf_sel`: 00=L0_A(weight), 01=L0_B(activation), 10=L0_C(result), 11=VEC
- `mem_addr`: UB base address (row 0), loads/stores N consecutive rows

### C-type (MATMUL)
```
[31:28] opcode  [27:0] reserved (0)
```
Computes C = A * W using pre-loaded L0_A (weight) and L0_B (activation) buffers.
Result stored internally, accessible via STORE L0_C.

### V-type (VECADD, RELU)
```
[31:28] opcode  [27:22] src1  [21:16] src2  [15:10] dst  [9:0] reserved
```
- `src1/src2/dst`: row index into internal result buffer

## Instructions

| Opcode | Hex | Mnemonic | Description |
|--------|-----|----------|-------------|
| 0000 | 0x0 | NOP | No operation |
| 0001 | 0x1 | HALT | Stop execution |
| 0010 | 0x2 | LOAD | Transfer N rows from UB to internal buffer |
| 0011 | 0x3 | STORE | Transfer N rows from internal buffer to UB |
| 0100 | 0x4 | MATMUL | 4x4 matrix multiply C = A * W (INT8→INT32) |
| 0101 | 0x5 | VECADD | Vector addition (4-wide, 32-bit) |
| 0110 | 0x6 | RELU | ReLU activation: max(0, x) |

## Architecture

- 4x4 weight-stationary systolic array (INT8 multiply, INT32 accumulate)
- Scalar Unit: sequential instruction execution FSM
- Vector Unit: single-cycle VECADD/RELU
- Unified Buffer: 1024 x 128-bit dual-port SRAM
- Instruction Memory: 256 x 32-bit

## Example Program

```asm
; C = A * W
LOAD  L0_B, 0, 0     ; Load A from UB[0..3]
LOAD  L0_A, 0, 4     ; Load W from UB[4..7]
MATMUL                ; Compute C = A * W
STORE L0_C, 0, 8     ; Store C to UB[8..11]
HALT
```
