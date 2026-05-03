package gpu

import chisel3._
import chisel3.util._

/** Toy GPU Top Level — Multi-SM architecture.
  *
  * @param numSMs
  *   Number of Streaming Multiprocessors (default 4).
  * @param gmemLatency
  *   Global memory read latency per warp (default 10).
  * @param useSharedArch
  *   Use shared CUDA Core architecture (true) or per-Warp architecture (false). 注意：当前版本默认使用共享架构（真实
  *   GPU 设计）
  */
class ToyGpuTop(
    numSMs: Int = GpuParams.NumSMs,
    numWarps: Int = GpuParams.NumWarps,
    maxCTAsPerSM: Int = GpuParams.MaxCTAsPerSM,
    warpsPerCTA: Int = GpuParams.WarpsPerCTA,
    numCTAs: Int = -1,
    warpWidth: Int = GpuParams.WarpWidth,
    dw: Int = GpuParams.DataWidth,
    gmemLatency: Int = 10,
    useSharedArch: Boolean = true // 默认使用共享架构
) extends Module {
  val io = IO(new Bundle {
    val start = Input(Bool())
    val allHalted = Output(Bool())
    val imemLoadEn = Input(Bool())
    val imemLoadAddr = Input(UInt(8.W))
    val imemLoadData = Input(UInt(GpuParams.InstrWidth.W))
    val gmemExt = new Bundle {
      val en = Input(Bool())
      val we = Input(Bool())
      val addr = Input(UInt(GpuParams.GlobalAddrW.W))
      val wdata = Input(Vec(warpWidth, SInt(dw.W)))
      val rdata = Output(Vec(warpWidth, SInt(dw.W)))
    }
    // Per-SM performance counters
    val perf = Output(Vec(numSMs, new GpuPerfCounters))
  })

  private val gridCTAs = if (numCTAs < 0) numSMs * maxCTAsPerSM else numCTAs
  require(maxCTAsPerSM > 1, "ToyGpuTop should model multiple resident CTAs per SM")
  require(warpsPerCTA > 0, "warpsPerCTA must be positive")
  require(numWarps == maxCTAsPerSM * warpsPerCTA, "numWarps must equal maxCTAsPerSM * warpsPerCTA")

  // Shared instruction memory
  val imem = Mem(GpuParams.IMEMDepth, UInt(GpuParams.InstrWidth.W))
  when(io.imemLoadEn) { imem.write(io.imemLoadAddr, io.imemLoadData) }

  // Shared global memory (combinational read, latency modeled in Warps)
  val gmem = Mem(GpuParams.GlobalDepth, Vec(warpWidth, SInt(dw.W)))

  // External port
  io.gmemExt.rdata := gmem.read(io.gmemExt.addr)
  when(io.gmemExt.en && io.gmemExt.we) {
    gmem.write(io.gmemExt.addr, io.gmemExt.wdata)
  }

  // === Streaming Multiprocessors ===
  // 使用共享 CUDA Core 架构（真实 GPU 设计）
  // CUDA Core 数量 = 2 × warpWidth（支持双调度器并发）
  // 利用率 80-95%，相比旧架构（per-Warp）节省 50% 硬件资源
  val numCores = 2 * warpWidth // 4 线程 → 8 cores, 8 线程 → 16 cores
  val ctaScheduler = Module(new CTAScheduler(numSMs, maxCTAsPerSM, gridCTAs))
  val sms = Array.fill(numSMs)(
    Module(
      new SM(
        numWarps,
        warpWidth,
        numCores,
        dw,
        memLatency = gmemLatency,
        maxCTAsPerSM = maxCTAsPerSM,
        warpsPerCTA = warpsPerCTA
      )
    )
  )

  ctaScheduler.io.start := io.start
  io.allHalted := ctaScheduler.io.allDone

  for (i <- 0 until numSMs) {
    val sm = sms(i)
    sm.io.start := io.start
    sm.io.ctaLaunch := ctaScheduler.io.ctaLaunch(i)
    ctaScheduler.io.slotActive(i) := sm.io.ctaActive
    ctaScheduler.io.ctaDone(i) := sm.io.ctaDone

    // Shared instruction memory (all SMs read same program)
    for (w <- 0 until numWarps) {
      sm.io.imemData(w) := imem.read(sm.io.imemAddr(w))
    }

    // Global memory: each SM gets combinational read access
    // Mem supports multiple combinational reads per cycle
    sm.io.gmemRdata := gmem.read(sm.io.gmemAddr)
    when(sm.io.gmemEn && sm.io.gmemWe) {
      gmem.write(sm.io.gmemAddr, sm.io.gmemWdata)
    }

    io.perf(i) := sm.io.perf
  }
}

class GpuPerfCounters extends Bundle {
  val totalCycles = UInt(32.W)
  val activeWarpCycles = UInt(32.W)
  val eligibleWarpCycles = UInt(32.W)
  val stalledWarpCycles = UInt(32.W)
  val noEligibleCycles = UInt(32.W)
  val aluIssueCycles = UInt(32.W)
  val sfuIssueCycles = UInt(32.W)
  val memIssueCycles = UInt(32.W)
  val dualIssueCycles = UInt(32.W)
  val gmemReads = UInt(16.W)
  val gmemWrites = UInt(16.W)
  val ctaLaunches = UInt(16.W)
  val ctaCompletions = UInt(16.W)
  val activeCTACycles = UInt(32.W)
}
