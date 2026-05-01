package ascend

import chisel3._
import chisel3.util._

/** Weight-Stationary Systolic Array: C = A * W (NxN matrices).
  *
  * PE[k][j] stores weight W[k][j].
  * Activation enters row k from the left, flows right (data).
  * Partial sums flow top-to-bottom (psum).
  *
  * The caller must provide skewed activations over 2N-1 cycles.
  * After feeding, the array drains for N more cycles.
  * C[i][j] appears at the bottom of column j at absolute cycle (i + j + N).
  */
class SystolicArray(
    n:  Int = AscendParams.ArraySize,
    dw: Int = AscendParams.DataWidth,
    aw: Int = AscendParams.AccWidth
) extends Module {
  val io = IO(new Bundle {
    val start       = Input(Bool())
    val done        = Output(Bool())
    val weightData  = Input(Vec(n, Vec(n, SInt(dw.W))))
    val actIn       = Input(Vec(n, SInt(dw.W)))
    val actValid    = Input(Bool())
    val result      = Output(Vec(n, Vec(n, SInt(aw.W))))
    val resultValid = Output(Bool())
  })

  // PE array
  val pes = Array.tabulate(n, n)((_, _) => Module(new PE(dw, aw)))

  // Horizontal activation wires (left to right): actH(row)(0..n)
  val actH = Wire(Vec(n, Vec(n + 1, SInt(dw.W))))
  // Vertical psum wires (top to bottom): psumV(0..n)(col)
  val psumV = Wire(Vec(n + 1, Vec(n, SInt(aw.W))))

  // FSM
  val sIdle :: sLoadWeight :: sCompute :: sDone :: Nil = Enum(4)
  val state    = RegInit(sIdle)
  val cycleCnt = RegInit(0.U(8.W))
  val drainCnt = RegInit(0.U(8.W))

  val feedCycles  = (2 * n - 1).U
  val drainCycles = n.U
  val feedingDone = cycleCnt === feedCycles

  val weightLoad = state === sLoadWeight

  switch(state) {
    is(sIdle) {
      when(io.start) {
        state    := sLoadWeight
        cycleCnt := 0.U
        drainCnt := 0.U
      }
    }
    is(sLoadWeight) {
      state := sCompute
    }
    is(sCompute) {
      when(!feedingDone) {
        when(io.actValid) { cycleCnt := cycleCnt + 1.U }
      }.otherwise {
        drainCnt := drainCnt + 1.U
        when(drainCnt === drainCycles) {
          state := sDone
        }
      }
    }
    is(sDone) {
      state := sIdle
    }
  }

  io.done        := state === sDone
  io.resultValid := state === sDone

  // Left side: activation inputs (zero during drain)
  for (k <- 0 until n) {
    actH(k)(0) := Mux(io.actValid && !feedingDone, io.actIn(k), 0.S)
  }

  // Top side: zero partial sums
  for (j <- 0 until n) {
    psumV(0)(j) := 0.S
  }

  // Connect PE array
  for (k <- 0 until n; j <- 0 until n) {
    pes(k)(j).io.weightLoad := weightLoad
    pes(k)(j).io.weightIn   := io.weightData(k)(j)
    pes(k)(j).io.dataIn     := actH(k)(j)
    actH(k)(j + 1)          := pes(k)(j).io.dataOut
    pes(k)(j).io.psumIn     := psumV(k)(j)
    psumV(k + 1)(j)         := pes(k)(j).io.psumOut
  }

  // Result capture
  // C[i][j] appears at psumV(n)(j) at absolute cycle (i + j + n)
  val absCycle  = Mux(feedingDone, feedCycles + drainCnt, cycleCnt)
  val resultReg = RegInit(VecInit.fill(n, n)(0.S(aw.W)))

  when(state === sCompute) {
    for (j <- 0 until n) {
      val rawIdx = absCycle - (j + n).U
      when(absCycle >= (j + n).U && rawIdx < n.U) {
        resultReg(rawIdx(log2Ceil(n) - 1, 0))(j) := psumV(n)(j)
      }
    }
  }

  io.result := resultReg
}
