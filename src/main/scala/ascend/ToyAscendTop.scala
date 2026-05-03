package ascend

import chisel3._
import chisel3.util._
import common.LatencyMem

/** Toy Ascend NPU Top Level — Multi-core architecture.
  *
  * Storage hierarchy:
  *   HBM (off-chip) ──ext preload──► L2Buffer (shared) ──DMA──► UB (per-core) ──► Compute
  *
  * The top-level ControlCpu models the device-side task/control plane. It owns
  * SPMD block dispatch and launches physical AiCores as execution slots.
  *
  * @param numCores   Number of AI Cores (default 2).
  * @param blockStride L2 row stride between SPMD logical blocks.
  * @param hbmLatency HBM read latency in cycles.
  */
class ToyAscendTop(
    n:          Int = AscendParams.ArraySize,
    dw:         Int = AscendParams.DataWidth,
    aw:         Int = AscendParams.AccWidth,
    numCores:   Int = AscendParams.NumCores,
    blockStride: Int = AscendParams.L2SliceSize,
    hbmLatency: Int = AscendParams.HBMLatency
) extends Module {
  val io = IO(new Bundle {
    val start  = Input(Bool())
    val blockDim = Input(UInt(AscendParams.BlockDimWidth.W))
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
    val dbgCoreActive = Output(Vec(numCores, Bool()))
    val dbgBlockIdx = Output(Vec(numCores, UInt(AscendParams.BlockDimWidth.W)))
    val dbgControlCpuRunning = Output(Bool())
    val dbgControlCpuNextBlock = Output(UInt(AscendParams.BlockDimWidth.W))
    val dbgControlCpuCompletedBlocks = Output(UInt(AscendParams.BlockDimWidth.W))
    val dbgAiCpuBusy = Output(Bool())
    val dbgAiCpuDone = Output(Bool())
  })

  // === Shared Instruction Memory ===
  // Combinational multi-read model so physical cores can execute different
  // logical SPMD blocks without staying in PC lockstep.
  val imem = Mem(AscendParams.IMEMDepth, UInt(AscendParams.InstrWidth.W))
  when(io.imemLoadEn) {
    imem.write(io.imemLoadAddr, io.imemLoadData)
  }

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

  // === AI CPU auxiliary engine ===
  // The toy top instantiates the device-side AI CPU block explicitly. Its task
  // input is tied off for now; standalone tests exercise the engine directly.
  val aiCpu = Module(new AiCpu(n, aw))
  aiCpu.io.cmd.valid := false.B
  aiCpu.io.cmd.bits := 0.U.asTypeOf(new AiCpuCommand)
  aiCpu.io.l2Rdata := l2.read(aiCpu.io.l2Addr)
  when(aiCpu.io.l2En && aiCpu.io.l2We) {
    l2.write(aiCpu.io.l2Addr, aiCpu.io.l2Wdata)
  }
  io.dbgAiCpuBusy := aiCpu.io.busy
  io.dbgAiCpuDone := aiCpu.io.done

  // === AI Cores ===
  val controlCpu = Module(new ControlCpu(numCores))
  val cores = Array.tabulate(numCores)(i => Module(new AiCore(n, dw, aw, coreId = i, blockStride = blockStride)))
  val ubs   = Array.fill(numCores)(Module(new UnifiedBuffer(n, aw, depth = AscendParams.UBDepth)))

  controlCpu.io.start := io.start
  controlCpu.io.blockDim := io.blockDim
  for (i <- 0 until numCores) {
    controlCpu.io.coreHalted(i) := cores(i).io.halted
  }

  io.halted := controlCpu.io.halted
  io.dbgCoreActive := controlCpu.io.coreActive
  io.dbgBlockIdx := controlCpu.io.coreBlockIdx
  io.dbgControlCpuRunning := controlCpu.io.dbgRunning
  io.dbgControlCpuNextBlock := controlCpu.io.dbgNextBlock
  io.dbgControlCpuCompletedBlocks := controlCpu.io.dbgCompletedBlocks

  for (i <- 0 until numCores) {
    val core = cores(i)
    val ub   = ubs(i)

    core.io.start := controlCpu.io.coreLaunch(i).valid
    core.io.blockIdx := Mux(controlCpu.io.coreLaunch(i).valid, controlCpu.io.coreLaunch(i).bits, controlCpu.io.coreBlockIdx(i))

    // Shared instruction memory: each core reads independently
    core.io.imemData := imem.read(core.io.imemAddr)

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
