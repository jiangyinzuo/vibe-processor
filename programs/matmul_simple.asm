; matmul_simple.asm - Load two 4x4 matrices, multiply, store result
; UB layout:
;   addr 0-3:   Matrix A (4 rows, each row = 4 int8 values packed in 128-bit word)
;   addr 4-7:   Matrix W (4 rows)
;   addr 8-11:  Result C = A * W (4 rows, 32-bit accumulators)

LOAD  L0_B, 0, 0     ; Load A from UB[0..3] into activation buffer
LOAD  L0_A, 0, 4     ; Load W from UB[4..7] into weight buffer
MATMUL                ; C = A * W
STORE L0_C, 0, 8     ; Store result to UB[8..11]
HALT
