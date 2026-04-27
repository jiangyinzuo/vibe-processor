// Toy Ascend NPU Top Level
// Integrates AI Core with Instruction Memory and Unified Buffer

module toy_ascend_top
  import ascend_pkg::*;
#(
  parameter int N = ARRAY_SIZE
)(
  input  logic                  clk,
  input  logic                  rst_n,
  input  logic                  start,
  output logic                  halted,

  // Instruction memory preload interface
  input  logic                  imem_load_en,
  input  logic [7:0]            imem_load_addr,
  input  logic [INSTR_WIDTH-1:0] imem_load_data,

  // Unified Buffer external port (port B for test preload/readback)
  input  logic                  ub_ext_en,
  input  logic                  ub_ext_we,
  input  logic [UB_ADDR_WIDTH-1:0] ub_ext_addr,
  input  logic [N*ACC_WIDTH-1:0] ub_ext_wdata,
  output logic [N*ACC_WIDTH-1:0] ub_ext_rdata
);

  // Internal wires
  logic [7:0]              imem_addr;
  logic [INSTR_WIDTH-1:0]  imem_data;
  logic                    ub_en, ub_we;
  logic [UB_ADDR_WIDTH-1:0] ub_addr;
  logic [N*ACC_WIDTH-1:0]  ub_wdata, ub_rdata;

  instr_mem u_imem (
    .clk       (clk),
    .addr      (imem_addr),
    .instr     (imem_data),
    .load_en   (imem_load_en),
    .load_addr (imem_load_addr),
    .load_data (imem_load_data)
  );

  unified_buffer u_ub (
    .clk          (clk),
    .rst_n        (rst_n),
    .port_a_en    (ub_en),
    .port_a_we    (ub_we),
    .port_a_addr  (ub_addr),
    .port_a_wdata (ub_wdata),
    .port_a_rdata (ub_rdata),
    .port_b_en    (ub_ext_en),
    .port_b_we    (ub_ext_we),
    .port_b_addr  (ub_ext_addr),
    .port_b_wdata (ub_ext_wdata),
    .port_b_rdata (ub_ext_rdata)
  );

  ai_core #(.N(N)) u_core (
    .clk       (clk),
    .rst_n     (rst_n),
    .start     (start),
    .halted    (halted),
    .imem_addr (imem_addr),
    .imem_data (imem_data),
    .ub_en     (ub_en),
    .ub_we     (ub_we),
    .ub_addr   (ub_addr),
    .ub_wdata  (ub_wdata),
    .ub_rdata  (ub_rdata)
  );

endmodule
