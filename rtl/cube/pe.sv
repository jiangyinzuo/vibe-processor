module pe
  import ascend_pkg::*;
(
  input  logic                  clk,
  input  logic                  rst_n,
  // Weight load
  input  logic                  weight_load,
  input  logic signed [DATA_WIDTH-1:0] weight_in,
  // Activation flow: top -> bottom
  input  logic signed [DATA_WIDTH-1:0] data_in,
  output logic signed [DATA_WIDTH-1:0] data_out,
  // Partial sum flow: left -> right
  input  logic signed [ACC_WIDTH-1:0]  psum_in,
  output logic signed [ACC_WIDTH-1:0]  psum_out
);

  logic signed [DATA_WIDTH-1:0] weight_reg;

  always_ff @(posedge clk or negedge rst_n) begin
    if (!rst_n) begin
      weight_reg <= '0;
      data_out   <= '0;
      psum_out   <= '0;
    end else begin
      if (weight_load)
        weight_reg <= weight_in;

      // Pipeline: pass activation down one cycle
      data_out <= data_in;
      // MAC: accumulate
      psum_out <= psum_in + ACC_WIDTH'(weight_reg) * ACC_WIDTH'(data_in);
    end
  end

endmodule
