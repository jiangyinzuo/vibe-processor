package ascend

import chisel3._

/** Toy Ascend NPU Top Level. */
class ToyAscendTop(
    n:  Int = AscendParams.ArraySize,
    dw: Int = AscendParams.DataWidth,
    aw: Int = AscendParams.AccWidth
) extends Module {
  val io = IO(new Bundle {
    val start  = Input(Bool())
    val halted = Output(Bool())
    // Instruction memory preload
    val imemLoadEn   = Input(Bool())
    val imemLoadAddr = Input(UInt(8.W))
    val imemLoadData = Input(UInt(AscendParams.InstrWidth.W))
    // UB external port (test preload/readback)
    val ubExt = new Bundle {
      val en    = Input(Bool())
      val we    = Input(Bool())
      val addr  = Input(UInt(AscendParams.UBAddrW.W))
      val wdata = Input(Vec(n, SInt(aw.W)))
      val rdata = Output(Vec(n, SInt(aw.W)))
    }
  })

  val imem = Module(new InstrMem)
  val ub   = Module(new UnifiedBuffer(n, aw))
  val core = Module(new AiCore(n, dw, aw))

  // Instruction memory
  imem.io.addr     := core.io.imemAddr
  core.io.imemData := imem.io.instr
  imem.io.loadEn   := io.imemLoadEn
  imem.io.loadAddr := io.imemLoadAddr
  imem.io.loadData := io.imemLoadData

  // Unified Buffer - Port A (core)
  ub.io.portA.en    := core.io.ubEn
  ub.io.portA.we    := core.io.ubWe
  ub.io.portA.addr  := core.io.ubAddr
  ub.io.portA.wdata := core.io.ubWdata
  core.io.ubRdata   := ub.io.portA.rdata

  // Unified Buffer - Port B (external)
  ub.io.portB.en    := io.ubExt.en
  ub.io.portB.we    := io.ubExt.we
  ub.io.portB.addr  := io.ubExt.addr
  ub.io.portB.wdata := io.ubExt.wdata
  io.ubExt.rdata    := ub.io.portB.rdata

  // Top-level
  core.io.start := io.start
  io.halted     := core.io.halted
}
