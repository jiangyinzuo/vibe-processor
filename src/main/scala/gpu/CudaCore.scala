package gpu

import chisel3._
import chisel3.util._

/** CUDA Core: single-lane ALU supporting ADD, MUL, MAD.
  * All operations are on 32-bit signed integers, single-cycle.
  */
class CudaCore(dw: Int = GpuParams.DataWidth) extends Module {
  val io = IO(new Bundle {
    val op   = Input(UInt(4.W))
    val rs1  = Input(SInt(dw.W))
    val rs2  = Input(SInt(dw.W))
    val rs3  = Input(SInt(dw.W))  // for MAD
    val rd   = Output(SInt(dw.W))
    val valid = Input(Bool())
    val done  = Output(Bool())
  })

  val result = WireDefault(0.S(dw.W))

  switch(io.op) {
    is(GpuOpcode.ADD) { result := io.rs1 + io.rs2 }
    is(GpuOpcode.MUL) { result := io.rs1 * io.rs2 }
    is(GpuOpcode.MAD) { result := io.rs1 * io.rs2 + io.rs3 }
  }

  io.rd   := RegNext(Mux(io.valid, result, 0.S), 0.S)
  io.done := RegNext(io.valid, false.B)
}
