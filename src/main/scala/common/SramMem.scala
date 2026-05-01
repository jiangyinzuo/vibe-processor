package common

import chisel3._
import chisel3.util.log2Ceil

/** Generic dual-port SRAM wrapper around SyncReadMem.
  * Read port is always active (combinational address, registered output).
  * Write port is gated by en && we.
  */
class DualPortSram[T <: Data](gen: T, depth: Int, addrW: Int) extends Module {
  val io = IO(new Bundle {
    val portA = new SramPort(gen, addrW)
    val portB = new SramPort(gen, addrW)
  })

  val mem = SyncReadMem(depth, gen)

  // Port A
  io.portA.rdata := mem.read(io.portA.addr)
  when(io.portA.en && io.portA.we) { mem.write(io.portA.addr, io.portA.wdata) }

  // Port B
  io.portB.rdata := mem.read(io.portB.addr)
  when(io.portB.en && io.portB.we) { mem.write(io.portB.addr, io.portB.wdata) }
}

/** Single SRAM port bundle. */
class SramPort[T <: Data](gen: T, addrW: Int) extends Bundle {
  val en    = Input(Bool())
  val we    = Input(Bool())
  val addr  = Input(UInt(addrW.W))
  val wdata = Input(gen)
  val rdata = Output(gen)
}

/** Single-port SRAM: combinational read, synchronous write. Good for instruction memory. */
class SinglePortRom(width: Int, depth: Int) extends Module {
  val io = IO(new Bundle {
    val addr     = Input(UInt(log2Ceil(depth).W))
    val data     = Output(UInt(width.W))
    val loadEn   = Input(Bool())
    val loadAddr = Input(UInt(log2Ceil(depth).W))
    val loadData = Input(UInt(width.W))
  })

  val mem = Mem(depth, UInt(width.W))
  io.data := mem.read(io.addr)
  when(io.loadEn) { mem.write(io.loadAddr, io.loadData) }
}
