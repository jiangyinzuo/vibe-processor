// Weight-Stationary Systolic Array: C = A * W  (NxN matrices)
//
// C[i][j] = sum_k( A[i][k] * W[k][j] )
//
// PE[k][j] stores weight W[k][j]
// Activation enters row k from the left, flows right
// Partial sums flow top-to-bottom
//
// Interfaces use flat packed vectors for Verilator/cocotb compatibility.
// weight_flat: N*N*DATA_WIDTH bits, row-major: W[0][0] is MSB side
// act_flat: N*DATA_WIDTH bits: act[0] is MSB side
// result_flat: N*N*ACC_WIDTH bits, row-major

module systolic_array
  import ascend_pkg::*;
#(
  parameter int N = ARRAY_SIZE
)(
  input  logic                  clk,
  input  logic                  rst_n,
  input  logic                  start,
  output logic                  done,
  // Flat packed interfaces
  input  logic [N*N*DATA_WIDTH-1:0]  weight_flat,
  input  logic [N*DATA_WIDTH-1:0]    act_flat,
  input  logic                       act_valid,
  output logic [N*N*ACC_WIDTH-1:0]   result_flat,
  output logic                       result_valid
);

  // Unpack weight and activation
  logic signed [DATA_WIDTH-1:0] weight_data [N][N];
  logic signed [DATA_WIDTH-1:0] act_in [N];

  always_comb begin
    for (int k = 0; k < N; k++) begin
      act_in[k] = act_flat[k*DATA_WIDTH +: DATA_WIDTH];
      for (int j = 0; j < N; j++)
        weight_data[k][j] = weight_flat[(k*N+j)*DATA_WIDTH +: DATA_WIDTH];
    end
  end

  // Horizontal activation wires (left to right)
  logic signed [DATA_WIDTH-1:0] act_h [N][N+1];
  // Vertical psum wires (top to bottom)
  logic signed [ACC_WIDTH-1:0]  psum_v [N+1][N];

  typedef enum logic [1:0] {
    S_IDLE,
    S_LOAD_WEIGHT,
    S_COMPUTE,
    S_DONE
  } state_t;

  state_t state, state_next;
  logic [7:0] cycle_cnt;
  logic [7:0] drain_cnt;
  logic feeding_done;

  localparam int FEED_CYCLES  = 2 * N - 1;
  localparam int DRAIN_CYCLES = N;

  always_ff @(posedge clk or negedge rst_n) begin
    if (!rst_n) begin
      state     <= S_IDLE;
      cycle_cnt <= '0;
      drain_cnt <= '0;
    end else begin
      state <= state_next;
      case (state)
        S_COMPUTE: begin
          if (!feeding_done) begin
            if (act_valid)
              cycle_cnt <= cycle_cnt + 1;
          end else begin
            drain_cnt <= drain_cnt + 1;
          end
        end
        default: begin
          cycle_cnt <= '0;
          drain_cnt <= '0;
        end
      endcase
    end
  end

  assign feeding_done = (cycle_cnt == FEED_CYCLES[7:0]);

  always_comb begin
    state_next = state;
    case (state)
      S_IDLE:        if (start) state_next = S_LOAD_WEIGHT;
      S_LOAD_WEIGHT: state_next = S_COMPUTE;
      S_COMPUTE:     if (feeding_done && drain_cnt == DRAIN_CYCLES[7:0])
                       state_next = S_DONE;
      S_DONE:        state_next = S_IDLE;
    endcase
  end

  logic weight_load;
  assign weight_load  = (state == S_LOAD_WEIGHT);
  assign done         = (state == S_DONE);
  assign result_valid = (state == S_DONE);

  // Left side: activation inputs (zero during drain)
  always_comb begin
    for (int k = 0; k < N; k++)
      act_h[k][0] = (act_valid && !feeding_done) ? act_in[k] : '0;
  end

  // Top side: zero partial sums
  always_comb begin
    for (int j = 0; j < N; j++)
      psum_v[0][j] = '0;
  end

  // PE array
  generate
    for (genvar k = 0; k < N; k++) begin : gen_row
      for (genvar j = 0; j < N; j++) begin : gen_col
        pe u_pe (
          .clk         (clk),
          .rst_n       (rst_n),
          .weight_load (weight_load),
          .weight_in   (weight_data[k][j]),
          .data_in     (act_h[k][j]),
          .data_out    (act_h[k][j+1]),
          .psum_in     (psum_v[k][j]),
          .psum_out    (psum_v[k+1][j])
        );
      end
    end
  endgenerate

  // Result capture
  // C[i][j] appears at psum_v[N][j] at absolute cycle (i + j + N)
  // where absolute cycle = cycle_cnt during feeding, FEED_CYCLES + drain_cnt during drain
  logic signed [ACC_WIDTH-1:0] result_reg [N][N];
  logic [7:0] abs_cycle;

  assign abs_cycle = feeding_done ? (FEED_CYCLES[7:0] + drain_cnt) : cycle_cnt;

  always_ff @(posedge clk or negedge rst_n) begin
    if (!rst_n) begin
      for (int i = 0; i < N; i++)
        for (int j = 0; j < N; j++)
          result_reg[i][j] <= '0;
    end else if (state == S_COMPUTE) begin
      for (int j = 0; j < N; j++) begin
        // C[i][j] valid when abs_cycle == i + j + N, so i = abs_cycle - j - N
        if (abs_cycle >= (j + N) && (abs_cycle - j - N) < N)
          result_reg[abs_cycle - j - N][j] <= psum_v[N][j];
      end
    end
  end

  // Pack results
  always_comb begin
    for (int i = 0; i < N; i++)
      for (int j = 0; j < N; j++)
        result_flat[(i*N+j)*ACC_WIDTH +: ACC_WIDTH] = result_reg[i][j];
  end

endmodule
