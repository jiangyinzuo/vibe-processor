package gpu

import chisel3._
import chisel3.util._

/** Streaming Multiprocessor - 共享架构版本
  *
  * 这是按照真实 GPU 架构重构的版本：
  *   - CUDA Core 作为 SM 级别的共享资源（16 个）
  *   - Warp 只是轻量级的执行上下文（不包含物理 ALU）
  *   - 寄存器文件是共享的，按 (WarpId, LaneId, RegId) 索引
  *   - 调度器选择 Ready 的 Warp，分发器分配 CUDA Core
  *
  * 架构对比：
  *   旧版本：4 Warp × 8 CUDA Core = 32 个 CUDA Core（利用率 25%）
  *   新版本：16 个共享 CUDA Core（利用率 80-95%）
  *
  * 资源节省：50% 硬件资源，3-4× 利用率提升
  */
class SM_Shared(
    numWarps:    Int = 4,
    warpWidth:   Int = 8,
    numCores:    Int = 16,
    dw:          Int = GpuParams.DataWidth,
    memLatency:  Int = 1
) extends Module {
  val io = IO(new Bundle {
    val start     = Input(Bool())
    val allHalted = Output(Bool())

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
  require(warpWidth == 4 || warpWidth == 8, s"Currently only supports warp width of 4 or 8, got $warpWidth")
  require(numCores == 16 || numCores == 8, s"Currently only supports 8 or 16 CUDA cores, got $numCores")

  // === 共享资源 ===
  val cudaCores = Array.fill(numCores)(Module(new CudaCore(dw)))
  val sfus = Array.fill(warpWidth)(Module(new SFU(dw)))  // 每个 lane 一个 SFU
  val regFile = Module(new SharedRegisterFile(numWarps, warpWidth, numRegs = 16, dw, numCores))
  val sharedMem = SyncReadMem(GpuParams.SharedDepth, Vec(warpWidth, SInt(dw.W)))

  // === Warp 上下文（轻量级）===
  val warpContexts = RegInit(VecInit.fill(numWarps)(WarpContext.init(warpWidth, dw)))

  // === 调度器（2 个，每个管理 2 个 Warp）===
  val numSchedulers = 2
  val warpsPerScheduler = numWarps / numSchedulers
  val schedulers = Array.fill(numSchedulers)(Module(new WarpScheduler(warpsPerScheduler)))

  // 连接调度器
  for (s <- 0 until numSchedulers) {
    for (w <- 0 until warpsPerScheduler) {
      val warpId = s * warpsPerScheduler + w
      // Warp 是 Halted 或 Stalled 时不能被调度
      schedulers(s).io.warpHalted(w) :=
        (warpContexts(warpId).state === WarpState.Halted) ||
        (warpContexts(warpId).state === WarpState.Stalled)
    }
  }

  // 合并调度器的 grant 信号
  val combinedGrant = Wire(Vec(numWarps, Bool()))
  for (s <- 0 until numSchedulers) {
    for (w <- 0 until warpsPerScheduler) {
      val warpId = s * warpsPerScheduler + w
      combinedGrant(warpId) := schedulers(s).io.grant(w)
    }
  }
  io.dbgGrant := combinedGrant

  // === 检测是否有内存写回 ===
  val hasMemWb = Wire(Bool())
  val memWbWarpId = Wire(UInt(log2Ceil(numWarps).W))
  hasMemWb := false.B
  memWbWarpId := 0.U

  for (i <- 0 until numWarps) {
    when(warpContexts(i).state === WarpState.Stalled && warpContexts(i).memWaitCounter === 0.U) {
      hasMemWb := true.B
      memWbWarpId := i.U
    }
  }

  // === 指令分发器 ===
  val dispatcher = Module(new InstructionDispatcher(numWarps, warpWidth, numCores, numSchedulers, memLatency))

  // 连接调度器到分发器
  // 当有内存写回时，禁止发射新指令（避免寄存器文件写端口冲突）
  for (s <- 0 until numSchedulers) {
    dispatcher.io.selectedWarp(s).valid := false.B
    dispatcher.io.selectedWarp(s).bits := 0.U

    when(!hasMemWb) {  // 只有在没有内存写回时才允许发射
      for (w <- 0 until warpsPerScheduler) {
        val warpId = s * warpsPerScheduler + w
        when(combinedGrant(warpId) && warpContexts(warpId).started) {
          dispatcher.io.selectedWarp(s).valid := true.B
          dispatcher.io.selectedWarp(s).bits := warpId.U
        }
      }
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

  // === 连接 CUDA Core 和 SFU ===
  // 当指令是 EXP 时，使用 SFU；否则使用 CUDA Core
  val isExpInstrVec = Wire(Vec(numCores, Bool()))
  val isExpInstrRegVec = RegNext(isExpInstrVec)  // 延迟 1 周期以匹配 SFU 延迟

  for (i <- 0 until numCores) {
    val isExpInstr = dispatcher.io.coreOp(i) === GpuOpcode.EXP
    isExpInstrVec(i) := isExpInstr

    // CUDA Core 连接（非 EXP 指令）
    cudaCores(i).io.valid := dispatcher.io.coreValid(i) && !isExpInstr
    cudaCores(i).io.op := dispatcher.io.coreOp(i)
    cudaCores(i).io.rs1 := dispatcher.io.coreRs1(i)
    cudaCores(i).io.rs2 := dispatcher.io.coreRs2(i)
    cudaCores(i).io.rs3 := dispatcher.io.coreRs3(i)

    // SFU 连接（EXP 指令）
    // 每个 core 对应一个 lane，使用对应的 SFU
    if (i < warpWidth) {
      sfus(i).io.valid := dispatcher.io.coreValid(i) && isExpInstr
      sfus(i).io.op := dispatcher.io.coreOp(i)
      sfus(i).io.x := dispatcher.io.coreRs1(i)
    }

    // 结果选择：EXP 指令使用 SFU 结果，其他使用 CUDA Core 结果
    // 使用延迟后的 isExpInstr 以匹配 SFU 的 1 周期延迟
    val sfuResult = if (i < warpWidth) sfus(i).io.result else 0.S
    val sfuDone = if (i < warpWidth) sfus(i).io.done else false.B

    dispatcher.io.coreDone(i) := Mux(isExpInstrRegVec(i), sfuDone, cudaCores(i).io.done)
    dispatcher.io.coreRd(i) := Mux(isExpInstrRegVec(i), sfuResult, cudaCores(i).io.rd)
  }

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

  // === Warp 上下文更新 ===
  // 启动信号
  when(io.start) {
    for (i <- 0 until numWarps) {
      warpContexts(i).started := true.B
    }
  }

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
    when(warpContexts(i).state === WarpState.Stalled) {
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

  // === 所有 Warp 是否都已停止 ===
  io.allHalted := VecInit(warpContexts.map(_.state === WarpState.Halted)).asUInt.andR

  // === 性能计数器 ===
  val totalCycles = RegInit(0.U(32.W))
  val activeWarpCycles = RegInit(0.U(32.W))
  val gmemReads = RegInit(0.U(16.W))
  val gmemWrites = RegInit(0.U(16.W))

  when(io.start) {
    totalCycles := 0.U
    activeWarpCycles := 0.U
    gmemReads := 0.U
    gmemWrites := 0.U
  }.elsewhen(!io.allHalted) {
    totalCycles := totalCycles + 1.U

    // 统计活跃的 Warp 数量
    val activeWarps = PopCount(VecInit(warpContexts.map(w =>
      w.state === WarpState.Ready || w.state === WarpState.Stalled
    )))
    activeWarpCycles := activeWarpCycles + activeWarps

    // 统计内存访问
    when(io.gmemEn) {
      when(io.gmemWe) {
        gmemWrites := gmemWrites + 1.U
      }.otherwise {
        gmemReads := gmemReads + 1.U
      }
    }
  }

  io.perf.totalCycles := totalCycles
  io.perf.activeWarpCycles := activeWarpCycles
  io.perf.gmemReads := gmemReads
  io.perf.gmemWrites := gmemWrites
}
