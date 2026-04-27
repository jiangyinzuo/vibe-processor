// Scalar Unit: instruction fetch, decode, and execution control FSM
// Sequentially executes instructions from instruction memory
// Controls Cube Unit, Vector Unit, and memory transfers

module scalar_unit
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

  // Unified Buffer interface
  output logic                  ub_en,
  output logic                  ub_we,
  output logic [UB_ADDR_WIDTH-1:0] ub_addr,
  output logic [N*ACC_WIDTH-1:0] ub_wdata,
  input  logic [N*ACC_WIDTH-1:0] ub_rdata,

  // Cube Unit interface
  output logic                  cube_start,
  input  logic                  cube_done,
  output logic [N*N*DATA_WIDTH-1:0] cube_weight,
  output logic [N*N*DATA_WIDTH-1:0] cube_act,
  input  logic [N*N*ACC_WIDTH-1:0]  cube_result,

  // Vector Unit interface
  output logic                  vec_start,
  input  logic                  vec_done,
  output logic [1:0]            vec_op,
  output logic [N*ACC_WIDTH-1:0] vec_src1,
  output logic [N*ACC_WIDTH-1:0] vec_src2,
  input  logic [N*ACC_WIDTH-1:0] vec_dst
);

  // Decode fields from instruction word
  logic [3:0]  op;
  logic [1:0]  dst_sel;
  logic [5:0]  reg_addr;
  logic [15:0] mem_addr;
  logic [3:0]  size_field;
  logic [5:0]  addr_a, addr_b, addr_c;
  logic [5:0]  vec_src1_addr, vec_src2_addr, vec_dst_addr;

  assign op       = imem_data[31:28];
  assign dst_sel  = imem_data[27:26];
  assign reg_addr = imem_data[25:20];
  assign mem_addr = imem_data[19:4];
  assign size_field = imem_data[3:0];
  // C-type fields (MATMUL)
  assign addr_a   = imem_data[27:22];
  assign addr_b   = imem_data[21:16];
  assign addr_c   = imem_data[15:10];
  // V-type fields (VECADD/RELU)
  assign vec_src1_addr = imem_data[27:22];
  assign vec_src2_addr = imem_data[21:16];
  assign vec_dst_addr  = imem_data[15:10];

  // FSM
  typedef enum logic [3:0] {
    S_IDLE,
    S_FETCH,
    S_DECODE,
    S_EXEC_LOAD_0,
    S_EXEC_LOAD_1,
    S_EXEC_LOAD_2,
    S_EXEC_STORE_0,
    S_EXEC_STORE_1,
    S_EXEC_STORE_2,
    S_EXEC_MATMUL,
    S_EXEC_VEC,
    S_HALTED
  } state_t;

  state_t state;
  logic [7:0] pc;

  // Latched instruction fields
  logic [3:0]  op_lat;
  logic [1:0]  dst_sel_lat;
  logic [5:0]  reg_addr_lat;
  logic [15:0] mem_addr_lat;
  logic [3:0]  size_lat;
  logic [5:0]  addr_a_lat, addr_b_lat, addr_c_lat;
  logic [5:0]  vec_src1_lat, vec_src2_lat, vec_dst_lat;

  // Row counter for multi-row LOAD/STORE
  logic [3:0] row_cnt;

  // Internal buffers for MATMUL operands (loaded from UB)
  logic [N*N*DATA_WIDTH-1:0] weight_buf;
  logic [N*N*DATA_WIDTH-1:0] act_buf;

  assign imem_addr = pc;
  assign halted = (state == S_HALTED);

  always_ff @(posedge clk or negedge rst_n) begin
    if (!rst_n) begin
      state       <= S_IDLE;
      pc          <= '0;
      op_lat      <= '0;
      row_cnt     <= '0;
      weight_buf  <= '0;
      act_buf     <= '0;
      ub_en       <= 0;
      ub_we       <= 0;
      ub_addr     <= '0;
      ub_wdata    <= '0;
      cube_start  <= 0;
      cube_weight <= '0;
      cube_act    <= '0;
      vec_start   <= 0;
      vec_op      <= '0;
      vec_src1    <= '0;
      vec_src2    <= '0;
    end else begin
      // Default: deassert one-shot signals
      ub_en      <= 0;
      ub_we      <= 0;
      cube_start <= 0;
      vec_start  <= 0;

      case (state)
        S_IDLE: begin
          if (start) begin
            pc    <= '0;
            state <= S_FETCH;
          end
        end

        S_FETCH: begin
          // imem_addr = pc, imem_data available combinationally
          state <= S_DECODE;
        end

        S_DECODE: begin
          // Latch decoded fields
          op_lat       <= op;
          dst_sel_lat  <= dst_sel;
          reg_addr_lat <= reg_addr;
          mem_addr_lat <= mem_addr;
          size_lat     <= size_field;
          addr_a_lat   <= addr_a;
          addr_b_lat   <= addr_b;
          addr_c_lat   <= addr_c;
          vec_src1_lat <= vec_src1_addr;
          vec_src2_lat <= vec_src2_addr;
          vec_dst_lat  <= vec_dst_addr;

          case (op)
            4'h0: begin // NOP
              pc    <= pc + 1;
              state <= S_FETCH;
            end
            4'h1: begin // HALT
              state <= S_HALTED;
            end
            4'h2: begin // LOAD: UB -> internal buffer
              row_cnt <= '0;
              state   <= S_EXEC_LOAD_0;
            end
            4'h3: begin // STORE: internal buffer -> UB
              row_cnt <= '0;
              state   <= S_EXEC_STORE_0;
            end
            4'h4: begin // MATMUL
              state <= S_EXEC_MATMUL;
            end
            4'h5, 4'h6: begin // VECADD, RELU
              state <= S_EXEC_VEC;
            end
            default: begin // Unknown -> NOP
              pc    <= pc + 1;
              state <= S_FETCH;
            end
          endcase
        end

        // LOAD: read N rows from UB into weight_buf or act_buf
        // Each row is N*DATA_WIDTH bits = N bytes, but UB word is N*ACC_WIDTH bits
        // We read N rows, each from UB[mem_addr + row]
        // and pack into the target buffer
        S_EXEC_LOAD_0: begin
          // Issue UB read for current row
          ub_en   <= 1;
          ub_we   <= 0;
          ub_addr <= mem_addr_lat + {12'b0, row_cnt};
          state   <= S_EXEC_LOAD_1;
        end

        S_EXEC_LOAD_1: begin
          // Wait for UB read data (1 cycle latency)
          state <= S_EXEC_LOAD_2;
        end

        S_EXEC_LOAD_2: begin
          // Store UB read data into target buffer
          // UB word is N*ACC_WIDTH bits, we extract lower N*DATA_WIDTH bits
          case (dst_sel_lat)
            2'b00: begin // L0_A (weight buffer)
              for (int j = 0; j < N; j++)
                weight_buf[(row_cnt*N+j)*DATA_WIDTH +: DATA_WIDTH]
                  <= ub_rdata[j*ACC_WIDTH +: DATA_WIDTH];
            end
            2'b01: begin // L0_B (activation buffer)
              for (int j = 0; j < N; j++)
                act_buf[(row_cnt*N+j)*DATA_WIDTH +: DATA_WIDTH]
                  <= ub_rdata[j*ACC_WIDTH +: DATA_WIDTH];
            end
            default: ;
          endcase

          if (row_cnt == N[3:0] - 1) begin
            pc      <= pc + 1;
            state   <= S_FETCH;
          end else begin
            row_cnt <= row_cnt + 1;
            state   <= S_EXEC_LOAD_0;
          end
        end

        // STORE: write N rows from cube_result to UB
        S_EXEC_STORE_0: begin
          // Prepare write data from cube result
          for (int j = 0; j < N; j++)
            ub_wdata[j*ACC_WIDTH +: ACC_WIDTH]
              <= cube_result[(row_cnt*N+j)*ACC_WIDTH +: ACC_WIDTH];
          ub_addr <= mem_addr_lat + {12'b0, row_cnt};
          ub_en   <= 1;
          ub_we   <= 1;
          state   <= S_EXEC_STORE_1;
        end

        S_EXEC_STORE_1: begin
          if (row_cnt == N[3:0] - 1) begin
            pc      <= pc + 1;
            state   <= S_FETCH;
          end else begin
            row_cnt <= row_cnt + 1;
            state   <= S_EXEC_STORE_0;
          end
        end

        // MATMUL: start cube unit and wait for done
        S_EXEC_MATMUL: begin
          cube_weight <= weight_buf;
          cube_act    <= act_buf;
          cube_start  <= 1;
          if (cube_done) begin
            cube_start <= 0;
            pc         <= pc + 1;
            state      <= S_FETCH;
          end
        end

        // VECADD/RELU: start vector unit and wait for done
        S_EXEC_VEC: begin
          vec_op   <= (op_lat == 4'h6) ? 2'd1 : 2'd0;
          // For simplicity, use cube_result as vector source
          // src1 = cube_result row[vec_src1_lat[1:0]]
          // src2 = cube_result row[vec_src2_lat[1:0]]
          for (int j = 0; j < N; j++) begin
            vec_src1[j*ACC_WIDTH +: ACC_WIDTH]
              <= cube_result[(vec_src1_lat[1:0]*N+j)*ACC_WIDTH +: ACC_WIDTH];
            vec_src2[j*ACC_WIDTH +: ACC_WIDTH]
              <= cube_result[(vec_src2_lat[1:0]*N+j)*ACC_WIDTH +: ACC_WIDTH];
          end
          vec_start <= 1;
          if (vec_done) begin
            vec_start <= 0;
            pc        <= pc + 1;
            state     <= S_FETCH;
          end
        end

        S_HALTED: begin
          // Stay halted until reset
        end

        default: state <= S_IDLE;
      endcase
    end
  end

endmodule
