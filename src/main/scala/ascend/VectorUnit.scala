package ascend

import chisel3._
import chisel3.util._

/** Vector Unit: single-cycle VECADD and RELU on N-wide vectors of ACC_WIDTH. */
class VectorUnit(
    n: Int = AscendParams.ArraySize,
    aw: Int = AscendParams.AccWidth
) extends Module {
  val io = IO(new Bundle {
    val start = Input(Bool())
    val op = Input(UInt(2.W)) // 0=VECADD, 1=RELU
    val done = Output(Bool())
    val src1 = Input(Vec(n, SInt(aw.W)))
    val src2 = Input(Vec(n, SInt(aw.W)))
    val dst = Output(Vec(n, SInt(aw.W)))
  })

  val sIdle :: sDone :: Nil = Enum(2)
  val state = RegInit(sIdle)
  val dstReg = RegInit(VecInit.fill(n)(0.S(aw.W)))

  switch(state) {
    is(sIdle) {
      when(io.start) {
        for (i <- 0 until n) {
          dstReg(i) := Mux(
            io.op === 1.U,
            Mux(io.src1(i) > 0.S, io.src1(i), 0.S), // RELU
            io.src1(i) + io.src2(i) // VECADD
          )
        }
        state := sDone
      }
    }
    is(sDone) {
      state := sIdle
    }
  }

  io.done := state === sDone
  io.dst := dstReg
}
