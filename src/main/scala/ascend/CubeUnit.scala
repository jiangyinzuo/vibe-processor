package ascend

import chisel3._
import chisel3.util._

/** Cube Unit: wraps SystolicArray, handles skewed activation feeding.
  *
  * The CubeUnit snapshots one L0A/L0B tile at launch. That models the dedicated Cube operand
  * staging path in a real NPU and keeps the upstream L0 tile FIFO from directly driving the PE
  * array's feed/weight paths in the same cycle.
  */
class CubeUnit(
    n: Int = AscendParams.ArraySize,
    dw: Int = AscendParams.DataWidth,
    aw: Int = AscendParams.AccWidth
) extends Module {
  val io = IO(new Bundle {
    val start = Input(Bool())
    val done = Output(Bool())
    val weightData = Input(Vec(n, Vec(n, SInt(dw.W))))
    val actData = Input(Vec(n, Vec(n, SInt(dw.W))))
    val result = Output(Vec(n, Vec(n, SInt(aw.W))))
    val resultValid = Output(Bool())
    // Debug
    val dbgFeeding = Output(Bool())
  })

  val sa = Module(new SystolicArray(n, dw, aw))

  // Cube 输入锁存寄存器：在 io.start 时用边沿触发寄存器采样一份 L0B/L0A tile，
  // 后续 SystolicArray 计算期间保持该 tile 稳定；这里不是透明 latch。
  val weightTileReg = RegInit(VecInit.fill(n, n)(0.S(dw.W)))
  val actTileReg = RegInit(VecInit.fill(n, n)(0.S(dw.W)))

  val sIdle :: sStartSA :: sWaitSA :: sFeed :: sDrain :: sDone :: Nil = Enum(6)
  val state = RegInit(sIdle)
  val feedCnt = RegInit(0.U(8.W))
  val feedCycles = (2 * n - 1).U

  switch(state) {
    is(sIdle) {
      when(io.start) {
        weightTileReg := io.weightData
        actTileReg := io.actData
        state := sStartSA
        feedCnt := 0.U
      }
    }
    is(sStartSA) { state := sWaitSA }
    is(sWaitSA) { state := sFeed }
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
  sa.io.weightData := weightTileReg
  sa.io.actValid := state === sFeed

  // Skewed activation: at cycle feedCnt, actIn(k) = A[feedCnt-k][k]
  for (k <- 0 until n) {
    val idx = (feedCnt - k.U)(log2Ceil(n) - 1, 0) // 扩展到 log2Ceil(n) bits
    val valid = state === sFeed && feedCnt >= k.U && (feedCnt - k.U) < n.U
    sa.io.actIn(k) := Mux(valid, actTileReg(idx)(k), 0.S(dw.W))
  }

  io.done := state === sDone
  io.dbgFeeding := state === sFeed
  io.result := sa.io.result
  io.resultValid := sa.io.resultValid
}
