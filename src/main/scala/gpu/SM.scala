package gpu

import chisel3._
import chisel3.util._

/** Streaming Multiprocessor - 共享架构版本
  *
  * 这是按照真实 GPU 架构重构的版本：
  *   - SM 内部显式拆分为多个 sub-partition
  *   - 每个 sub-partition 拥有自己的 WarpScheduler、CUDA Core 和 SFU lane group
  *   - Warp 只是轻量级的执行上下文（不包含物理 ALU）
  *   - 寄存器文件是共享的，按 (WarpId, LaneId, RegId) 索引
  *   - SharedMem 和 GlobalMem 请求路径仍由 SM 顶层统一仲裁
  */
class SM(
    numWarps:    Int = 4,
    warpWidth:   Int = 8,
    numCores:    Int = 16,
    dw:          Int = GpuParams.DataWidth,
    memLatency:  Int = 1,
    maxCTAsPerSM: Int = GpuParams.MaxCTAsPerSM,
    warpsPerCTA: Int = GpuParams.WarpsPerCTA
) extends Module {
  val io = IO(new Bundle {
    val start     = Input(Bool())
    val allHalted = Output(Bool())

    // Resident CTA slots inside this SM.
    val ctaLaunch = Input(Vec(maxCTAsPerSM, Valid(UInt(GpuParams.CTAIdWidth.W))))
    val ctaDone   = Output(Vec(maxCTAsPerSM, Valid(UInt(GpuParams.CTAIdWidth.W))))
    val ctaActive = Output(Vec(maxCTAsPerSM, Bool()))
    val ctaId     = Output(Vec(maxCTAsPerSM, UInt(GpuParams.CTAIdWidth.W)))

    // 指令内存（每个 Warp 一个端口）
    val imemAddr  = Output(Vec(numWarps, UInt(8.W)))
    val imemData  = Input(Vec(numWarps, UInt(GpuParams.InstrWidth.W)))

    // Global memory
    val gmemEn    = Output(Bool())
    val gmemWe    = Output(Bool())
    val gmemAddr  = Output(UInt(GpuParams.GlobalAddrW.W))
    val gmemWdata = Output(Vec(warpWidth, SInt(dw.W)))
    val gmemRdata = Input(Vec(warpWidth, SInt(dw.W)))

    // Performance counters
    val perf = Output(new GpuPerfCounters)

    // Debug
    val dbgGrant  = Output(Vec(numWarps, Bool()))
  })

  require(numWarps == 4, "Currently only supports 4 warps")
  require(maxCTAsPerSM > 1, "SM should model multiple resident CTAs")
  require(warpsPerCTA > 0, "warpsPerCTA must be positive")
  require(numWarps == maxCTAsPerSM * warpsPerCTA, "numWarps must equal maxCTAsPerSM * warpsPerCTA")
  require(warpWidth == 4 || warpWidth == 8, s"Currently only supports warp width of 4 or 8, got $warpWidth")
  require(numCores == 16 || numCores == 8, s"Currently only supports 8 or 16 CUDA cores, got $numCores")

  val numSubPartitions = numCores / warpWidth
  require(numCores % warpWidth == 0, "numCores must be a multiple of warpWidth")
  require(numWarps % numSubPartitions == 0, "numWarps must be divisible by numSubPartitions")

  val warpsPerSubPartition = numWarps / numSubPartitions

  // === 共享资源 ===
  val numRfPorts = warpWidth + 2 * numCores
  val regFile = Module(new SharedRegisterFile(numWarps, warpWidth, numRegs = 16, dw, numRfPorts))
  val sharedMem = SyncReadMem(GpuParams.SharedDepth, Vec(warpWidth, SInt(dw.W)))

  // === Warp 上下文（轻量级）===
  val warpContexts = RegInit(VecInit.fill(numWarps)(WarpContext.init(warpWidth, dw)))
  val fullActiveMask = ((BigInt(1) << warpWidth) - 1).U(8.W)

  // === CTA / thread block residency ===
  val ctaActive = RegInit(VecInit.fill(maxCTAsPerSM)(false.B))
  val ctaIds = RegInit(VecInit.fill(maxCTAsPerSM)(0.U(GpuParams.CTAIdWidth.W)))

  for (w <- 0 until numWarps) {
    regFile.io.initWarp(w) := false.B
    regFile.io.initBlockId(w) := 0.U
    regFile.io.initWarpIdxInCTA(w) := 0.U
  }

  // === SM sub-partitions ===
  val subPartitions = Array.tabulate(numSubPartitions) { p =>
    Module(new SMSubPartition(p, numWarps, warpsPerSubPartition, warpWidth, dw))
  }

  // === 检测是否有内存写回 ===
  val hasMemWb = Wire(Bool())
  val memWbWarpId = Wire(UInt(log2Ceil(numWarps).W))
  hasMemWb := false.B
  memWbWarpId := 0.U

  for (i <- 0 until numWarps) {
    when(warpContexts(i).started && warpContexts(i).state === WarpState.Stalled && warpContexts(i).memWaitCounter === 0.U) {
      hasMemWb := true.B
      memWbWarpId := i.U
    }
  }

  // === 指令分发器 ===
  val dispatcher = Module(new InstructionDispatcher(numWarps, warpWidth, numCores, numSubPartitions, memLatency))

  // 连接 sub-partitions 到分发器和执行单元切片
  // 当有内存写回时，禁止发射新指令（避免寄存器文件写端口冲突）
  for (p <- 0 until numSubPartitions) {
    val subPartition = subPartitions(p)
    subPartition.io.issueBlocked := hasMemWb || dispatcher.io.issueBusy(p).asUInt.andR
    dispatcher.io.selectedWarp(p) := subPartition.io.selectedWarp

    for (w <- 0 until warpsPerSubPartition) {
      val warpId = p * warpsPerSubPartition + w
      subPartition.io.warpState(w) := warpContexts(warpId).state
      subPartition.io.warpStarted(w) := warpContexts(warpId).started
      io.dbgGrant(warpId) := subPartition.io.grant(w) && warpContexts(warpId).started
    }

    for (lane <- 0 until warpWidth) {
      val coreId = p * warpWidth + lane
      subPartition.io.coreValid(lane) := dispatcher.io.coreValid(coreId)
      subPartition.io.coreOp(lane) := dispatcher.io.coreOp(coreId)
      subPartition.io.coreRs1(lane) := dispatcher.io.coreRs1(coreId)
      subPartition.io.coreRs2(lane) := dispatcher.io.coreRs2(coreId)
      subPartition.io.coreRs3(lane) := dispatcher.io.coreRs3(coreId)
      dispatcher.io.coreDone(coreId) := subPartition.io.coreDone(lane)
      dispatcher.io.coreRd(coreId) := subPartition.io.coreRd(lane)

      subPartition.io.sfuValid(lane) := dispatcher.io.sfuValid(coreId)
      subPartition.io.sfuOp(lane) := dispatcher.io.sfuOp(coreId)
      subPartition.io.sfuRs1(lane) := dispatcher.io.sfuRs1(coreId)
      dispatcher.io.sfuDone(coreId) := subPartition.io.sfuDone(lane)
      dispatcher.io.sfuRd(coreId) := subPartition.io.sfuRd(lane)
    }
  }

  // 连接 Warp 上下文到分发器
  for (i <- 0 until numWarps) {
    dispatcher.io.warpPC(i) := warpContexts(i).pc
    dispatcher.io.warpState(i) := warpContexts(i).state
  }

  // 连接指令内存
  io.imemAddr := dispatcher.io.imemAddr
  dispatcher.io.imemData := io.imemData

  // === 连接寄存器文件 ===
  regFile.io.rdAddr := dispatcher.io.regRdAddr
  dispatcher.io.regRdData := regFile.io.rdData
  regFile.io.wrAddr := dispatcher.io.regWrAddr
  regFile.io.wrData := dispatcher.io.regWrData

  // === 内存访问处理 ===
  io.gmemEn := false.B
  io.gmemWe := false.B
  io.gmemAddr := 0.U
  io.gmemWdata := VecInit.fill(warpWidth)(0.S)

  when(dispatcher.io.memReq.valid) {
    io.gmemEn := true.B
    io.gmemWe := !dispatcher.io.memReq.bits.isLoad
    io.gmemAddr := dispatcher.io.memReq.bits.addr
    when(dispatcher.io.memReq.bits.isLoad) {
      // LOAD: 锁存读取的数据到 WarpContext
      val warpId = dispatcher.io.memReq.bits.warpId
      warpContexts(warpId).memRdData := io.gmemRdata
    }.otherwise {
      // STORE: 写入数据
      io.gmemWdata := dispatcher.io.memWdata
    }
  }

  // === CTA 完成检测 ===
  val ctaDoneVec = Wire(Vec(maxCTAsPerSM, Bool()))
  for (slot <- 0 until maxCTAsPerSM) {
    val baseWarp = slot * warpsPerCTA
    ctaDoneVec(slot) :=
      ctaActive(slot) &&
        VecInit((0 until warpsPerCTA).map { w =>
          warpContexts(baseWarp + w).state === WarpState.Halted
        }).asUInt.andR

    io.ctaDone(slot).valid := ctaDoneVec(slot)
    io.ctaDone(slot).bits := ctaIds(slot)
    io.ctaActive(slot) := ctaActive(slot)
    io.ctaId(slot) := ctaIds(slot)
  }

  // === Warp 上下文更新 ===
  // 来自分发器的更新
  for (i <- 0 until numWarps) {
    when(dispatcher.io.warpUpdate(i).valid) {
      when(dispatcher.io.warpUpdate(i).pcInc) {
        warpContexts(i).pc := warpContexts(i).pc + 1.U
      }
      when(dispatcher.io.warpUpdate(i).setState.valid) {
        warpContexts(i).state := dispatcher.io.warpUpdate(i).setState.bits
      }
      when(dispatcher.io.warpUpdate(i).setMemWait.valid) {
        warpContexts(i).memWaitCounter := dispatcher.io.warpUpdate(i).setMemWait.bits
      }
      when(dispatcher.io.warpUpdate(i).setMemRd.valid) {
        warpContexts(i).memRdReg := dispatcher.io.warpUpdate(i).setMemRd.bits
      }
    }
  }

  // === 内存等待处理 ===
  // 当 Warp 处于 Stalled 状态时，递减计数器
  // 内存数据写回：使用寄存器文件的前 warpWidth 个端口
  // 由于 hasMemWb 会阻止 dispatcher 发射新指令，所以不会有写端口冲突
  for (i <- 0 until numWarps) {
    when(warpContexts(i).started && warpContexts(i).state === WarpState.Stalled) {
      when(warpContexts(i).memWaitCounter === 0.U) {
        // 内存访问完成，写回数据到寄存器文件
        for (lane <- 0 until warpWidth) {
          regFile.io.wrAddr(lane).valid := true.B
          regFile.io.wrAddr(lane).warpId := i.U
          regFile.io.wrAddr(lane).laneId := lane.U
          regFile.io.wrAddr(lane).rd := warpContexts(i).memRdReg
          regFile.io.wrData(lane) := warpContexts(i).memRdData(lane)  // 使用缓冲的数据
        }
        // 恢复到 Ready 状态
        warpContexts(i).state := WarpState.Ready
        warpContexts(i).pc := warpContexts(i).pc + 1.U
      }.otherwise {
        warpContexts(i).memWaitCounter := warpContexts(i).memWaitCounter - 1.U
      }
    }
  }

  // === Grid start / CTA slot launch / CTA completion ===
  when(io.start) {
    for (slot <- 0 until maxCTAsPerSM) {
      ctaActive(slot) := false.B
      ctaIds(slot) := 0.U
    }
    for (w <- 0 until numWarps) {
      warpContexts(w) := WarpContext.init(warpWidth, dw)
    }
  }

  for (slot <- 0 until maxCTAsPerSM) {
    when(ctaDoneVec(slot)) {
      ctaActive(slot) := false.B
      for (localWarp <- 0 until warpsPerCTA) {
        val warpId = slot * warpsPerCTA + localWarp
        warpContexts(warpId).started := false.B
      }
    }
  }

  for (slot <- 0 until maxCTAsPerSM) {
    when(io.ctaLaunch(slot).valid) {
      ctaActive(slot) := true.B
      ctaIds(slot) := io.ctaLaunch(slot).bits

      for (localWarp <- 0 until warpsPerCTA) {
        val warpId = slot * warpsPerCTA + localWarp

        warpContexts(warpId).started := true.B
        warpContexts(warpId).pc := 0.U
        warpContexts(warpId).state := WarpState.Ready
        warpContexts(warpId).activeMask := fullActiveMask
        warpContexts(warpId).ctaId := io.ctaLaunch(slot).bits
        warpContexts(warpId).memWaitCounter := 0.U
        warpContexts(warpId).memRdReg := 0.U
        warpContexts(warpId).memRdData := VecInit(Seq.fill(warpWidth)(0.S(dw.W)))

        regFile.io.initWarp(warpId) := true.B
        regFile.io.initBlockId(warpId) := io.ctaLaunch(slot).bits
        regFile.io.initWarpIdxInCTA(warpId) := localWarp.U
      }
    }
  }

  // === 所有 CTA slot 是否都已空闲 ===
  io.allHalted := !ctaActive.asUInt.orR

  // === 性能计数器 ===
  val totalCycles = RegInit(0.U(32.W))
  val activeWarpCycles = RegInit(0.U(32.W))
  val gmemReads = RegInit(0.U(16.W))
  val gmemWrites = RegInit(0.U(16.W))
  val ctaLaunches = RegInit(0.U(16.W))
  val ctaCompletions = RegInit(0.U(16.W))
  val activeCTACycles = RegInit(0.U(32.W))
  val smHasActiveCTA = ctaActive.asUInt.orR
  val ctaLaunchCount = PopCount(io.ctaLaunch.map(_.valid))
  val ctaCompletionCount = PopCount(ctaDoneVec)

  when(io.start) {
    totalCycles := 0.U
    activeWarpCycles := 0.U
    gmemReads := 0.U
    gmemWrites := 0.U
    ctaLaunches := ctaLaunchCount
    ctaCompletions := 0.U
    activeCTACycles := 0.U
  }.elsewhen(smHasActiveCTA) {
    totalCycles := totalCycles + 1.U

    // 统计活跃的 Warp 数量
    val activeWarps = PopCount(VecInit(warpContexts.map(w =>
      w.started && (w.state === WarpState.Ready || w.state === WarpState.Stalled)
    )))
    activeWarpCycles := activeWarpCycles + activeWarps
    activeCTACycles := activeCTACycles + PopCount(ctaActive)

    // 统计内存访问
    when(io.gmemEn) {
      when(io.gmemWe) {
        gmemWrites := gmemWrites + 1.U
      }.otherwise {
        gmemReads := gmemReads + 1.U
      }
    }
  }
  when(!io.start) {
    ctaLaunches := ctaLaunches + ctaLaunchCount
    ctaCompletions := ctaCompletions + ctaCompletionCount
  }

  io.perf.totalCycles := totalCycles
  io.perf.activeWarpCycles := activeWarpCycles
  io.perf.gmemReads := gmemReads
  io.perf.gmemWrites := gmemWrites
  io.perf.ctaLaunches := ctaLaunches
  io.perf.ctaCompletions := ctaCompletions
  io.perf.activeCTACycles := activeCTACycles
}
