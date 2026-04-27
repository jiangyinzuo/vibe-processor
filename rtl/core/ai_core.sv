// AI Core: integrates Scalar Unit, Cube Unit, Vector Unit
// and connects them to Unified Buffer and Instruction Memory

module ai_core
  import ascend_pkg::*;
#(
  parameter int N = ARRAY_SIZE
)(
  input  logic                  clk,
  input  logic                  rst_n,
  input  logic                  start,
  output logic                  halted,

  // Instruction memory interface
  output logic [7:0]            imem_addr,
  input  logic [INSTR_WIDTH-1:0] imem_data,

  // Unified Buffer port A (scalar unit access)
  output logic                  ub_en,
  output logic                  ub_we,
  output logic [UB_ADDR_WIDTH-1:0] ub_addr,
  output logic [N*ACC_WIDTH-1:0] ub_wdata,
  input  logic [N*ACC_WIDTH-1:0] ub_rdata
);

  // Cube Unit signals
  logic                      cube_start, cube_done;
  logic [N*N*DATA_WIDTH-1:0] cube_weight, cube_act;
  logic [N*N*ACC_WIDTH-1:0]  cube_result;
  logic                      cube_result_valid;

  // Vector Unit signals
  logic                      vec_start, vec_done;
  logic [1:0]                vec_op;
  logic [N*ACC_WIDTH-1:0]    vec_src1, vec_src2, vec_dst;

  scalar_unit #(.N(N)) u_scalar (
    .clk         (clk),
    .rst_n       (rst_n),
    .start       (start),
    .halted      (halted),
    .imem_addr   (imem_addr),
    .imem_data   (imem_data),
    .ub_en       (ub_en),
    .ub_we       (ub_we),
    .ub_addr     (ub_addr),
    .ub_wdata    (ub_wdata),
    .ub_rdata    (ub_rdata),
    .cube_start  (cube_start),
    .cube_done   (cube_done),
    .cube_weight (cube_weight),
    .cube_act    (cube_act),
    .cube_result (cube_result),
    .vec_start   (vec_start),
    .vec_done    (vec_done),
    .vec_op      (vec_op),
    .vec_src1    (vec_src1),
    .vec_src2    (vec_src2),
    .vec_dst     (vec_dst)
  );

  cube_unit #(.N(N)) u_cube (
    .clk          (clk),
    .rst_n        (rst_n),
    .start        (cube_start),
    .done         (cube_done),
    .weight_flat  (cube_weight),
    .act_flat     (cube_act),
    .result_flat  (cube_result),
    .result_valid (cube_result_valid)
  );

  vector_unit #(.N(N)) u_vector (
    .clk       (clk),
    .rst_n     (rst_n),
    .start     (vec_start),
    .op        (vec_op),
    .done      (vec_done),
    .src1_flat (vec_src1),
    .src2_flat (vec_src2),
    .dst_flat  (vec_dst)
  );

endmodule
