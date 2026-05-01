package ascend

import chisel3._
import chisel3.util._
import common.LatencyMem

/** Toy Ascend NPU Top Level — Multi-core architecture.
  *
  * Storage hierarchy:
  *   HBM (off-chip) ──ext preload──► L2Buffer (shared) ──DMA──► UB (per-core) ──► Compute
  *
  * @param numCores   Number of AI Cores (default 2).
  * @param hbmLatency HBM read latency in cycles.
  */
class ToyAscendTop(
    n:          Int = AscendParams.ArraySize,
    dw:         Int = AscendParams.DataWidth,
    aw:         Int = AscendParams.AccWidth,
    numCores:   Int = AscendParams.NumCores,
    hbmLatency: Int = AscendParams.HBMLatency
) extends Module {
  val io = IO(new Bundle {
    val start  = Input(Bool())
    val halted = Output(Bool())  // true when ALL cores halted
    // Instruction memory preload
    val imemLoadEn   = Input(Bool())
    val imemLoadAddr = Input(UInt(8.W))
    val imemLoadData = Input(UInt(AscendParams.InstrWidth.W))
    // L2 external port (test preload/readback of shared on-chip buffer)
    val l2Ext = new Bundle {
      val en    = Input(Bool())
      val we    = Input(Bool())
      val addr  = Input(UInt(AscendParams.L2AddrW.W))
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
    // Per-core performance counters
    val perf = Output(Vec(numCores, new PerfCounters))
  })

  // === Shared Instruction Memory ===
  val imem = Module(new InstrMem)
  imem.io.loadEn   := io.imemLoadEn
  imem.io.loadAddr := io.imemLoadAddr
  imem.io.loadData := io.imemLoadData

  // === HBM (off-chip, with latency) ===
  val hbm = Module(new LatencyMem(
    gen = Vec(n, SInt(aw.W)), depth = AscendParams.HBMDepth,
    latency = hbmLatency, addrW = AscendParams.HBMAddrW
  ))
  // HBM external port (direct access for test preload)
  hbm.io.direct.en    := io.hbmExt.en
  hbm.io.direct.we    := io.hbmExt.we
  hbm.io.direct.addr  := io.hbmExt.addr
  hbm.io.direct.wdata := io.hbmExt.wdata
  io.hbmExt.rdata     := hbm.io.direct.rdata
  // HBM req port — not used by cores (they access L2); driven by external or idle
  hbm.io.req.valid := false.B
  hbm.io.req.we    := false.B
  hbm.io.req.addr  := 0.U
  hbm.io.req.wdata := VecInit.fill(n)(0.S(aw.W))

  // === Shared L2 Buffer (on-chip, multi-port for cores + external) ===
  // Use a simple Mem (combinational read) to allow multiple cores to access per cycle
  val l2 = Mem(AscendParams.L2Depth, Vec(n, SInt(aw.W)))

  // L2 external port
  io.l2Ext.rdata := l2.read(io.l2Ext.addr)
  when(io.l2Ext.en && io.l2Ext.we) {
    l2.write(io.l2Ext.addr, io.l2Ext.wdata)
  }

  // === AI Cores ===
  val cores = Array.tabulate(numCores)(i => Module(new AiCore(n, dw, aw, coreId = i)))
  val ubs   = Array.fill(numCores)(Module(new UnifiedBuffer(n, aw, depth = AscendParams.UBDepth)))

  val coreHalted = VecInit(cores.map(_.io.halted))
  io.halted := coreHalted.asUInt.andR

  for (i <- 0 until numCores) {
    val core = cores(i)
    val ub   = ubs(i)

    core.io.start := io.start

    // Shared instruction memory: each core reads independently
    // Since all cores run the same program, they'll read the same instructions
    // We give each core its own read port (Mem supports multiple combinational reads)
    core.io.imemData := imem.io.instr
    if (i == 0) imem.io.addr := core.io.imemAddr

    // Per-core UB (dual-port: A for Scalar, B for DMA)
    ub.io.portA.en    := core.io.ubEn
    ub.io.portA.we    := core.io.ubWe
    ub.io.portA.addr  := core.io.ubAddr
    ub.io.portA.wdata := core.io.ubWdata
    core.io.ubRdata   := ub.io.portA.rdata

    ub.io.portB.en    := core.io.ubEnB
    ub.io.portB.we    := core.io.ubWeB
    ub.io.portB.addr  := core.io.ubAddrB
    ub.io.portB.wdata := core.io.ubWdataB
    core.io.ubRdataB  := ub.io.portB.rdata

    // Core ↔ L2
    core.io.l2Rdata := l2.read(core.io.l2Addr)
    when(core.io.l2En && core.io.l2We) {
      l2.write(core.io.l2Addr, core.io.l2Wdata)
    }

    io.perf(i) := core.io.perf
  }
}
