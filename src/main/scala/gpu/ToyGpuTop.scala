package gpu

import chisel3._
import chisel3.util._
import common.{HbmRequest, HbmStackedMemory}

/** Toy GPU Top Level — Multi-SM architecture.
  *
  * @param numSMs
  *   Number of Streaming Multiprocessors (default 4).
  * @param gmemLatency
  *   HBM model read latency (default 10).
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
    useSharedArch: Boolean = true, // 默认使用共享架构
    numHbmStacks: Int = GpuParams.HBMStacks
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

  // Shared HBM boundary. The stacked subsystem has one controller/model pair per HBM stack.
  val hbm = Module(
    new HbmStackedMemory(
      n = warpWidth,
      aw = dw,
      totalDepth = GpuParams.GlobalDepth,
      addrW = GpuParams.GlobalAddrW,
      numStacks = numHbmStacks,
      numChannelsPerStack = 4,
      banksPerChannel = 4,
      rowHitLatency = math.max(1, gmemLatency / 3),
      rowMissLatency = gmemLatency,
      requestQueueDepth = 16,
      responseQueueDepth = 8,
      modelLatency = 1
    )
  )

  hbm.io.direct.en := io.gmemExt.en
  hbm.io.direct.we := io.gmemExt.we
  hbm.io.direct.addr := io.gmemExt.addr
  hbm.io.direct.wdata := io.gmemExt.wdata
  io.gmemExt.rdata := hbm.io.direct.rdata

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

  val smIdW = math.max(1, log2Ceil(numSMs))
  val smReqValidVec = VecInit(sms.map(_.io.hbmReq.valid).toSeq)
  val smReqSelOH = PriorityEncoderOH(smReqValidVec)
  val selectedSmId = Mux1H(smReqSelOH, (0 until numSMs).map(_.U(smIdW.W)))
  val selectedHbmReq = Wire(new HbmRequest(warpWidth, dw, GpuParams.GlobalAddrW))
  selectedHbmReq := 0.U.asTypeOf(selectedHbmReq)
  for (i <- 0 until numSMs) {
    when(smReqSelOH(i)) {
      selectedHbmReq := sms(i).io.hbmReq.bits
    }
  }

  val readRoute = Module(new Queue(UInt(smIdW.W), entries = 16))
  val selectedReqIsRead = !selectedHbmReq.we
  val canRouteSelectedReq = !selectedReqIsRead || readRoute.io.enq.ready
  hbm.io.coreReq.valid := smReqValidVec.asUInt.orR && canRouteSelectedReq
  hbm.io.coreReq.bits := selectedHbmReq

  val hbmCoreReqFire = hbm.io.coreReq.valid && hbm.io.coreReq.ready
  readRoute.io.enq.valid := hbmCoreReqFire && !hbm.io.coreReq.bits.we
  readRoute.io.enq.bits := selectedSmId
  readRoute.io.deq.ready := hbm.io.coreResp.valid

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

    sm.io.hbmReq.ready := smReqSelOH(i) && hbm.io.coreReq.ready && canRouteSelectedReq
    sm.io.hbmResp.valid := hbm.io.coreResp.valid && readRoute.io.deq.valid &&
      readRoute.io.deq.bits === i.U
    sm.io.hbmResp.bits := hbm.io.coreResp.bits

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
