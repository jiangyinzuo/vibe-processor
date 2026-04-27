module l0_buffer
  import ascend_pkg::*;
#(
  parameter int DEPTH     = L0_DEPTH,
  parameter int WORD_BITS = DATA_WIDTH * ARRAY_SIZE
)(
  input  logic                  clk,
  input  logic                  rst_n,
  // Write port
  input  logic                  wr_en,
  input  logic [$clog2(DEPTH)-1:0] wr_addr,
  input  logic [WORD_BITS-1:0]  wr_data,
  // Read port
  input  logic                  rd_en,
  input  logic [$clog2(DEPTH)-1:0] rd_addr,
  output logic [WORD_BITS-1:0]  rd_data
);

  logic [WORD_BITS-1:0] mem [DEPTH];

  always_ff @(posedge clk) begin
    if (wr_en)
      mem[wr_addr] <= wr_data;
  end

  always_ff @(posedge clk) begin
    if (rd_en)
      rd_data <= mem[rd_addr];
  end

endmodule
