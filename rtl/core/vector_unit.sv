// Vector Unit: supports VECADD and RELU operations
// Operates on vectors of ARRAY_SIZE elements, ACC_WIDTH bits each

module vector_unit
  import ascend_pkg::*;
#(
  parameter int N = ARRAY_SIZE
)(
  input  logic                  clk,
  input  logic                  rst_n,
  // Control
  input  logic                  start,
  input  logic [1:0]            op,       // 0=VECADD, 1=RELU
  output logic                  done,
  // Operands (flat packed, N * ACC_WIDTH bits)
  input  logic [N*ACC_WIDTH-1:0] src1_flat,
  input  logic [N*ACC_WIDTH-1:0] src2_flat,
  output logic [N*ACC_WIDTH-1:0] dst_flat
);

  logic signed [ACC_WIDTH-1:0] src1 [N];
  logic signed [ACC_WIDTH-1:0] src2 [N];
  logic signed [ACC_WIDTH-1:0] dst  [N];

  // Unpack
  always_comb begin
    for (int i = 0; i < N; i++) begin
      src1[i] = src1_flat[i*ACC_WIDTH +: ACC_WIDTH];
      src2[i] = src2_flat[i*ACC_WIDTH +: ACC_WIDTH];
    end
  end

  // Pack
  always_comb begin
    for (int i = 0; i < N; i++)
      dst_flat[i*ACC_WIDTH +: ACC_WIDTH] = dst[i];
  end

  // Single-cycle compute
  typedef enum logic [1:0] {
    S_IDLE,
    S_EXEC,
    S_DONE
  } state_t;

  state_t state;

  always_ff @(posedge clk or negedge rst_n) begin
    if (!rst_n) begin
      state <= S_IDLE;
      for (int i = 0; i < N; i++)
        dst[i] <= '0;
    end else begin
      case (state)
        S_IDLE: begin
          if (start) begin
            for (int i = 0; i < N; i++) begin
              case (op)
                2'd0: dst[i] <= src1[i] + src2[i];           // VECADD
                2'd1: dst[i] <= (src1[i] > 0) ? src1[i] : 0; // RELU
                default: dst[i] <= '0;
              endcase
            end
            state <= S_DONE;
          end
        end
        S_DONE: begin
          state <= S_IDLE;
        end
        default: state <= S_IDLE;
      endcase
    end
  end

  assign done = (state == S_DONE);

endmodule
