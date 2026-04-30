package ascend

import chisel3._
import common.LatencyMem

/** Toy Ascend NPU Top Level.
  *
  * Storage hierarchy:
  *   HBM (off-chip, LatencyMem) ←DMA→ UB (on-chip, SyncReadMem) ← Compute
  *
  * @param hbmLatency HBM read latency in cycles (default 10).
  */
class ToyAscendTop(
    n:          Int = AscendParams.ArraySize,
    dw:         Int = AscendParams.DataWidth,
    aw:         Int = AscendParams.AccWidth,
    hbmLatency: Int = AscendParams.HBMLatency
) extends Module {
  val io = IO(new Bundle {
    val start  = Input(Bool())
    val halted = Output(Bool())
    // Instruction memory preload
    val imemLoadEn   = Input(Bool())
    val imemLoadAddr = Input(UInt(8.W))
    val imemLoadData = Input(UInt(AscendParams.InstrWidth.W))
    // UB external port (direct on-chip access for debug)
    val ubExt = new Bundle {
      val en    = Input(Bool())
      val we    = Input(Bool())
      val addr  = Input(UInt(AscendParams.UBAddrW.W))
      val wdata = Input(Vec(n, SInt(aw.W)))
      val rdata = Output(Vec(n, SInt(aw.W)))
    }
    // HBM external port (test preload/readback of off-chip memory)
    val hbmExt = new Bundle {
      val en    = Input(Bool())
      val we    = Input(Bool())
      val addr  = Input(UInt(AscendParams.HBMAddrW.W))
      val wdata = Input(Vec(n, SInt(aw.W)))
      val rdata = Output(Vec(n, SInt(aw.W)))
    }
    val perf = Output(new PerfCounters)
  })

  val imem = Module(new InstrMem)
  val ub   = Module(new UnifiedBuffer(n, aw))
  val hbm  = Module(new LatencyMem(
    gen     = Vec(n, SInt(aw.W)),
    depth   = AscendParams.HBMDepth,
    latency = hbmLatency,
    addrW   = AscendParams.HBMAddrW
  ))
  val core = Module(new AiCore(n, dw, aw))

  // Instruction memory
  imem.io.addr     := core.io.imemAddr
  core.io.imemData := imem.io.instr
  imem.io.loadEn   := io.imemLoadEn
  imem.io.loadAddr := io.imemLoadAddr
  imem.io.loadData := io.imemLoadData

  // Unified Buffer - Port A (core: Scalar + DMA)
  ub.io.portA.en    := core.io.ubEn
  ub.io.portA.we    := core.io.ubWe
  ub.io.portA.addr  := core.io.ubAddr
  ub.io.portA.wdata := core.io.ubWdata
  core.io.ubRdata   := ub.io.portA.rdata

  // Unified Buffer - Port B (external debug)
  ub.io.portB.en    := io.ubExt.en
  ub.io.portB.we    := io.ubExt.we
  ub.io.portB.addr  := io.ubExt.addr
  ub.io.portB.wdata := io.ubExt.wdata
  io.ubExt.rdata    := ub.io.portB.rdata

  // HBM (LatencyMem) - DMA port
  hbm.io.req.valid := core.io.hbmReqValid
  hbm.io.req.we    := core.io.hbmReqWe
  hbm.io.req.addr  := core.io.hbmReqAddr
  hbm.io.req.wdata := core.io.hbmReqWdata
  core.io.hbmReqReady  := hbm.io.req.ready
  core.io.hbmRespValid := hbm.io.resp.valid
  core.io.hbmRespRdata := hbm.io.resp.rdata

  // HBM - External port (test preload/readback via direct access, no latency)
  hbm.io.direct.en    := io.hbmExt.en
  hbm.io.direct.we    := io.hbmExt.we
  hbm.io.direct.addr  := io.hbmExt.addr
  hbm.io.direct.wdata := io.hbmExt.wdata
  io.hbmExt.rdata     := hbm.io.direct.rdata

  // Top-level
  core.io.start := io.start
  io.halted     := core.io.halted
  io.perf       := core.io.perf
}
