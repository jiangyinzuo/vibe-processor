// Cube Unit: wraps systolic array with L0 buffers for weight/activation/result
// Provides a simple start/done interface for the scalar unit

module cube_unit
  import ascend_pkg::*;
#(
  parameter int N = ARRAY_SIZE
)(
  input  logic                  clk,
  input  logic                  rst_n,
  // Control from scalar unit
  input  logic                  start,
  output logic                  done,
  // L0 buffer interfaces (flat packed for Verilator compatibility)
  // Weight buffer: N*N values of DATA_WIDTH
  input  logic [N*N*DATA_WIDTH-1:0]  weight_flat,
  // Activation buffer: N*N values of DATA_WIDTH (row-major, A[row][col])
  input  logic [N*N*DATA_WIDTH-1:0]  act_flat,
  // Result: N*N values of ACC_WIDTH
  output logic [N*N*ACC_WIDTH-1:0]   result_flat,
  output logic                       result_valid
);

  // Internal signals
  logic sa_start, sa_done;
  logic [N*N*DATA_WIDTH-1:0] sa_weight;
  logic [N*DATA_WIDTH-1:0]   sa_act;
  logic                      sa_act_valid;

  // Unpack activation matrix for skewed feeding
  logic signed [DATA_WIDTH-1:0] act_matrix [N][N];
  always_comb begin
    for (int i = 0; i < N; i++)
      for (int j = 0; j < N; j++)
        act_matrix[i][j] = act_flat[(i*N+j)*DATA_WIDTH +: DATA_WIDTH];
  end

  // FSM to orchestrate: load weights -> feed skewed activations -> collect results
  typedef enum logic [2:0] {
    S_IDLE,
    S_START_SA,
    S_WAIT_SA,
    S_FEED,
    S_DRAIN,
    S_DONE
  } state_t;

  state_t state;
  logic [7:0] feed_cnt;
  localparam int FEED_CYCLES = 2 * N - 1;

  always_ff @(posedge clk or negedge rst_n) begin
    if (!rst_n) begin
      state    <= S_IDLE;
      feed_cnt <= '0;
    end else begin
      case (state)
        S_IDLE: begin
          if (start) begin
            state    <= S_START_SA;
            feed_cnt <= '0;
          end
        end
        S_START_SA: begin
          // SA sees start=1, transitions IDLE -> LOAD_WEIGHT
          state <= S_WAIT_SA;
        end
        S_WAIT_SA: begin
          // SA transitions LOAD_WEIGHT -> COMPUTE, now ready for act_valid
          state <= S_FEED;
        end
        S_FEED: begin
          feed_cnt <= feed_cnt + 1;
          if (feed_cnt == FEED_CYCLES[7:0] - 1)
            state <= S_DRAIN;
        end
        S_DRAIN: begin
          if (sa_done)
            state <= S_DONE;
        end
        S_DONE: begin
          state <= S_IDLE;
        end
        default: state <= S_IDLE;
      endcase
    end
  end

  // Drive systolic array
  assign sa_start = (state == S_START_SA);
  assign sa_weight = weight_flat;
  assign sa_act_valid = (state == S_FEED);

  // Generate skewed activation: at cycle t, act_in[k] = A[t-k][k]
  always_comb begin
    for (int k = 0; k < N; k++) begin
      automatic int i = int'(feed_cnt) - k;
      if (state == S_FEED && i >= 0 && i < N)
        sa_act[k*DATA_WIDTH +: DATA_WIDTH] = act_matrix[i][k];
      else
        sa_act[k*DATA_WIDTH +: DATA_WIDTH] = '0;
    end
  end

  assign done = (state == S_DONE);

  systolic_array #(.N(N)) u_sa (
    .clk          (clk),
    .rst_n        (rst_n),
    .start        (sa_start),
    .done         (sa_done),
    .weight_flat  (sa_weight),
    .act_flat     (sa_act),
    .act_valid    (sa_act_valid),
    .result_flat  (result_flat),
    .result_valid (result_valid)
  );

endmodule
