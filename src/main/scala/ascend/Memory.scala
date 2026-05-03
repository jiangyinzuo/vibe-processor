package ascend

import chisel3._

/** Unified Buffer: dual-port synchronous SRAM. Each word is N * ACC_WIDTH bits (Vec of N
  * SInt(ACC_WIDTH)).
  */
class UnifiedBuffer(
    n: Int = AscendParams.ArraySize,
    aw: Int = AscendParams.AccWidth,
    depth: Int = AscendParams.UBDepth,
    addrW: Int = AscendParams.UBAddrW
) extends Module {
  val io = IO(new Bundle {
    // Port A (scalar unit)
    val portA = new Bundle {
      val en = Input(Bool())
      val we = Input(Bool())
      val addr = Input(UInt(addrW.W))
      val wdata = Input(Vec(n, SInt(aw.W)))
      val rdata = Output(Vec(n, SInt(aw.W)))
    }
    // Port B (external / test)
    val portB = new Bundle {
      val en = Input(Bool())
      val we = Input(Bool())
      val addr = Input(UInt(addrW.W))
      val wdata = Input(Vec(n, SInt(aw.W)))
      val rdata = Output(Vec(n, SInt(aw.W)))
    }
  })

  val mem = SyncReadMem(depth, Vec(n, SInt(aw.W)))

  // Port A: read is always active, write is gated by en && we
  io.portA.rdata := mem.read(io.portA.addr)
  when(io.portA.en && io.portA.we) {
    mem.write(io.portA.addr, io.portA.wdata)
  }

  // Port B: same pattern
  io.portB.rdata := mem.read(io.portB.addr)
  when(io.portB.en && io.portB.we) {
    mem.write(io.portB.addr, io.portB.wdata)
  }
}

/** Instruction Memory: combinational read, synchronous write (for preloading). */
class InstrMem(depth: Int = AscendParams.IMEMDepth) extends Module {
  val io = IO(new Bundle {
    val addr = Input(UInt(8.W))
    val instr = Output(UInt(AscendParams.InstrWidth.W))
    val loadEn = Input(Bool())
    val loadAddr = Input(UInt(8.W))
    val loadData = Input(UInt(AscendParams.InstrWidth.W))
  })

  val mem = Mem(depth, UInt(AscendParams.InstrWidth.W))

  io.instr := mem.read(io.addr)

  when(io.loadEn) {
    mem.write(io.loadAddr, io.loadData)
  }
}
