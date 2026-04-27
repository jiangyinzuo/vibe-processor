module instr_mem
  import ascend_pkg::*;
(
  input  logic                    clk,
  // Read port (PC driven)
  input  logic [7:0]              addr,
  output logic [INSTR_WIDTH-1:0]  instr,
  // Preload port
  input  logic                    load_en,
  input  logic [7:0]              load_addr,
  input  logic [INSTR_WIDTH-1:0]  load_data
);

  logic [INSTR_WIDTH-1:0] mem [IMEM_DEPTH];

  // Combinational read for instruction fetch
  assign instr = mem[addr];

  // Synchronous write for preloading
  always_ff @(posedge clk) begin
    if (load_en)
      mem[load_addr] <= load_data;
  end

endmodule
