package ascend

import chisel3._
import chisel3.util._

/** Cube Unit: wraps SystolicArray, handles skewed activation feeding. */
class CubeUnit(
    n:  Int = AscendParams.ArraySize,
    dw: Int = AscendParams.DataWidth,
    aw: Int = AscendParams.AccWidth
) extends Module {
  val io = IO(new Bundle {
    val start       = Input(Bool())
    val done        = Output(Bool())
    val weightData  = Input(Vec(n, Vec(n, SInt(dw.W))))
    val actData     = Input(Vec(n, Vec(n, SInt(dw.W))))
    val result      = Output(Vec(n, Vec(n, SInt(aw.W))))
    val resultValid = Output(Bool())
  })

  val sa = Module(new SystolicArray(n, dw, aw))

  val sIdle :: sStartSA :: sWaitSA :: sFeed :: sDrain :: sDone :: Nil = Enum(6)
  val state   = RegInit(sIdle)
  val feedCnt = RegInit(0.U(8.W))
  val feedCycles = (2 * n - 1).U

  switch(state) {
    is(sIdle) {
      when(io.start) {
        state   := sStartSA
        feedCnt := 0.U
      }
    }
    is(sStartSA) { state := sWaitSA }
    is(sWaitSA)  { state := sFeed }
    is(sFeed) {
      feedCnt := feedCnt + 1.U
      when(feedCnt === feedCycles - 1.U) { state := sDrain }
    }
    is(sDrain) {
      when(sa.io.done) { state := sDone }
    }
    is(sDone) { state := sIdle }
  }

  // Drive systolic array
  sa.io.start := state === sStartSA
  sa.io.weightData := io.weightData
  sa.io.actValid := state === sFeed

  // Skewed activation: at cycle feedCnt, actIn(k) = A[feedCnt-k][k]
  for (k <- 0 until n) {
    val idx = (feedCnt - k.U)(1, 0) // truncate to 2 bits for Vec(4) indexing
    val valid = state === sFeed && feedCnt >= k.U && (feedCnt - k.U) < n.U
    sa.io.actIn(k) := Mux(valid, io.actData(idx)(k), 0.S(dw.W))
  }

  io.done        := state === sDone
  io.result      := sa.io.result
  io.resultValid := sa.io.resultValid
}
