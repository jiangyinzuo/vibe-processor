module unified_buffer
  import ascend_pkg::*;
#(
  parameter int WORD_BITS = ACC_WIDTH * ARRAY_SIZE  // 128 bits per word
)(
  input  logic                          clk,
  input  logic                          rst_n,
  // Port A: internal (Scalar unit)
  input  logic                          port_a_en,
  input  logic                          port_a_we,
  input  logic [UB_ADDR_WIDTH-1:0]      port_a_addr,
  input  logic [WORD_BITS-1:0]          port_a_wdata,
  output logic [WORD_BITS-1:0]          port_a_rdata,
  // Port B: external (test preload)
  input  logic                          port_b_en,
  input  logic                          port_b_we,
  input  logic [UB_ADDR_WIDTH-1:0]      port_b_addr,
  input  logic [WORD_BITS-1:0]          port_b_wdata,
  output logic [WORD_BITS-1:0]          port_b_rdata
);

  localparam int ADDR_BITS = $clog2(UB_DEPTH);

  logic [WORD_BITS-1:0] mem [UB_DEPTH];

  // Port A
  always_ff @(posedge clk) begin
    if (port_a_en) begin
      if (port_a_we)
        mem[port_a_addr[ADDR_BITS-1:0]] <= port_a_wdata;
      else
        port_a_rdata <= mem[port_a_addr[ADDR_BITS-1:0]];
    end
  end

  // Port B
  always_ff @(posedge clk) begin
    if (port_b_en) begin
      if (port_b_we)
        mem[port_b_addr[ADDR_BITS-1:0]] <= port_b_wdata;
      else
        port_b_rdata <= mem[port_b_addr[ADDR_BITS-1:0]];
    end
  end

endmodule
