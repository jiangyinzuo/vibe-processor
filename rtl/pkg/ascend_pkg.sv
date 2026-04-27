package ascend_pkg;

  parameter int DATA_WIDTH    = 8;
  parameter int ACC_WIDTH     = 32;
  parameter int ARRAY_SIZE    = 4;
  parameter int INSTR_WIDTH   = 32;
  parameter int UB_ADDR_WIDTH = 16;
  parameter int UB_DEPTH      = 1024;
  parameter int L0_DEPTH      = 64;
  parameter int VEC_DEPTH     = 64;
  parameter int IMEM_DEPTH    = 256;

  typedef enum logic [3:0] {
    OP_NOP    = 4'h0,
    OP_HALT   = 4'h1,
    OP_LOAD   = 4'h2,
    OP_STORE  = 4'h3,
    OP_MATMUL = 4'h4,
    OP_VECADD = 4'h5,
    OP_RELU   = 4'h6
  } opcode_t;

  typedef enum logic [1:0] {
    BUF_L0_A = 2'b00,
    BUF_L0_B = 2'b01,
    BUF_L0_C = 2'b10,
    BUF_VEC  = 2'b11
  } buf_sel_t;

  typedef struct packed {
    opcode_t       opcode;
    buf_sel_t      dst_sel;
    logic [5:0]    reg_addr;
    logic [15:0]   mem_addr;
    logic [3:0]    size;
    logic [5:0]    addr_a;
    logic [5:0]    addr_b;
    logic [5:0]    addr_c;
    logic [5:0]    vec_src1;
    logic [5:0]    vec_src2;
    logic [5:0]    vec_dst;
  } decoded_instr_t;

endpackage
