package ascend

import chisel3._
import chisel3.util._

object AiCpuOp {
  val FILL = 0.U(2.W)
  val COPY = 1.U(2.W)
  val ADD_IMM = 2.U(2.W)
}

class AiCpuCommand(addrW: Int = AscendParams.L2AddrW) extends Bundle {
  val op = UInt(2.W)
  val src = UInt(addrW.W)
  val dst = UInt(addrW.W)
  val rows = UInt(8.W)
  val imm = SInt(AscendParams.AccWidth.W)
}

/** Toy AI CPU auxiliary engine.
  *
  * This is not a full ARM64 implementation. It models the architectural role that matters in this
  * project: a device-side CPU-like engine that can run simple branch/control-heavy memory tasks on
  * device memory while AiCores remain specialized for Cube/Vector kernels.
  */
class AiCpu(
    n: Int = AscendParams.ArraySize,
    aw: Int = AscendParams.AccWidth,
    addrW: Int = AscendParams.L2AddrW
) extends Module {
  val io = IO(new Bundle {
    val cmd = Flipped(Valid(new AiCpuCommand(addrW)))
    val busy = Output(Bool())
    val done = Output(Bool())

    val l2En = Output(Bool())
    val l2We = Output(Bool())
    val l2Addr = Output(UInt(addrW.W))
    val l2Wdata = Output(Vec(n, SInt(aw.W)))
    val l2Rdata = Input(Vec(n, SInt(aw.W)))

    val dbgRow = Output(UInt(8.W))
  })

  val sIdle :: sRead :: sWrite :: sDone :: Nil = Enum(4)
  val state = RegInit(sIdle)
  val cmdLat = RegInit(0.U.asTypeOf(new AiCpuCommand(addrW)))
  val rowIdx = RegInit(0.U(8.W))
  val readRow = RegInit(VecInit.fill(n)(0.S(aw.W)))

  val isFill = cmdLat.op === AiCpuOp.FILL
  val isAddImm = cmdLat.op === AiCpuOp.ADD_IMM
  val lastRow = rowIdx === cmdLat.rows - 1.U

  io.l2En := false.B
  io.l2We := false.B
  io.l2Addr := 0.U
  io.l2Wdata := VecInit.fill(n)(0.S(aw.W))
  io.busy := state =/= sIdle && state =/= sDone
  io.done := state === sDone
  io.dbgRow := rowIdx

  switch(state) {
    is(sIdle) {
      when(io.cmd.valid) {
        cmdLat := io.cmd.bits
        rowIdx := 0.U
        state := Mux(
          io.cmd.bits.rows === 0.U,
          sDone,
          Mux(io.cmd.bits.op === AiCpuOp.FILL, sWrite, sRead)
        )
      }
    }

    is(sRead) {
      io.l2En := true.B
      io.l2Addr := cmdLat.src + rowIdx
      readRow := io.l2Rdata
      state := sWrite
    }

    is(sWrite) {
      val copyRow = Mux(isAddImm, VecInit(readRow.map(_ + cmdLat.imm)), readRow)
      val fillRow = VecInit.fill(n)(cmdLat.imm)

      io.l2En := true.B
      io.l2We := true.B
      io.l2Addr := cmdLat.dst + rowIdx
      io.l2Wdata := Mux(isFill, fillRow, copyRow)

      when(lastRow) {
        state := sDone
      }.otherwise {
        rowIdx := rowIdx + 1.U
        state := Mux(isFill, sWrite, sRead)
      }
    }

    is(sDone) {
      state := sIdle
    }
  }
}
