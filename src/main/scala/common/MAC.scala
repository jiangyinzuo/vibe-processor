package common

import chisel3._

/** Multiply-Accumulate unit: out = a * b + c (single cycle, registered output).
  * Reusable across NPU PE and GPU CUDA Core.
  */
class MAC(dataW: Int = Params.DataWidth, accW: Int = Params.AccWidth) extends Module {
  val io = IO(new Bundle {
    val a   = Input(SInt(dataW.W))
    val b   = Input(SInt(dataW.W))
    val c   = Input(SInt(accW.W))
    val out = Output(SInt(accW.W))
  })
  io.out := RegNext(io.c + io.a * io.b, 0.S(accW.W))
}
