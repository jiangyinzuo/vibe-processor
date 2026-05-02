package gpu

import chisel3._
import chisel3.util._

/** 指令分发器
  *
  * 真实 GPU 中，Warp Scheduler 选择就绪的 Warp 后，
  * Instruction Dispatcher 负责：
  *   1. 从指令内存读取指令
  *   2. 解码指令
  *   3. 从寄存器文件读取操作数
  *   4. 将指令分发到空闲的 CUDA Core
  *   5. 处理 CUDA Core 的结果写回
  *
  * 这是 GPU 流水线的核心模块，实现了 Warp 到 CUDA Core 的映射。
  */
class InstructionDispatcher(
    numWarps: Int = 4,
    warpWidth: Int = 8,
    numCores: Int = 16,
    numSchedulers: Int = 2,
    gmemLatency: Int = 10
) extends Module {
  require(numWarps % numSchedulers == 0, "numWarps must be divisible by numSchedulers")
  val localWarps = numWarps / numSchedulers
  val rfPorts = warpWidth + 2 * numCores
  val aluPortBase = warpWidth
  val sfuPortBase = warpWidth + numCores

  val io = IO(new Bundle {
    // === 来自调度器的选中 Warp ===
    val selectedWarp = Input(Vec(numSchedulers, Vec(localWarps, Valid(UInt(log2Ceil(numWarps).W)))))

    // === Warp 上下文（只读）===
    val warpPC = Input(Vec(numWarps, UInt(8.W)))
    val warpState = Input(Vec(numWarps, WarpState()))

    // === 指令内存接口 ===
    val imemAddr = Output(Vec(numWarps, UInt(8.W)))
    val imemData = Input(Vec(numWarps, UInt(GpuParams.InstrWidth.W)))

    // === 到寄存器文件的读请求 ===
    val regRdAddr = Output(Vec(rfPorts, new Bundle {
      val valid  = Bool()
      val warpId = UInt(log2Ceil(numWarps).W)
      val laneId = UInt(log2Ceil(warpWidth).W)
      val rs1    = UInt(4.W)
      val rs2    = UInt(4.W)
      val rs3    = UInt(4.W)
    }))
    val regRdData = Input(Vec(rfPorts, new Bundle {
      val rs1 = SInt(32.W)
      val rs2 = SInt(32.W)
      val rs3 = SInt(32.W)
    }))

    // === 到 CUDA Core 的分发 ===
    val coreValid  = Output(Vec(numCores, Bool()))
    val coreOp     = Output(Vec(numCores, UInt(4.W)))
    val coreRs1    = Output(Vec(numCores, SInt(32.W)))
    val coreRs2    = Output(Vec(numCores, SInt(32.W)))
    val coreRs3    = Output(Vec(numCores, SInt(32.W)))
    val coreWarpId = Output(Vec(numCores, UInt(log2Ceil(numWarps).W)))
    val coreLaneId = Output(Vec(numCores, UInt(log2Ceil(warpWidth).W)))

    // === 到 SFU 的分发 ===
    val sfuValid  = Output(Vec(numCores, Bool()))
    val sfuOp     = Output(Vec(numCores, UInt(4.W)))
    val sfuRs1    = Output(Vec(numCores, SInt(32.W)))
    val sfuWarpId = Output(Vec(numCores, UInt(log2Ceil(numWarps).W)))
    val sfuLaneId = Output(Vec(numCores, UInt(log2Ceil(warpWidth).W)))

    // === 来自 CUDA Core 的结果 ===
    val coreDone   = Input(Vec(numCores, Bool()))
    val coreRd     = Input(Vec(numCores, SInt(32.W)))

    // === 来自 SFU 的结果 ===
    val sfuDone   = Input(Vec(numCores, Bool()))
    val sfuRd     = Input(Vec(numCores, SInt(32.W)))

    // === 到寄存器文件的写请求 ===
    val regWrAddr = Output(Vec(rfPorts, new Bundle {
      val valid  = Bool()
      val warpId = UInt(log2Ceil(numWarps).W)
      val laneId = UInt(log2Ceil(warpWidth).W)
      val rd     = UInt(4.W)
    }))
    val regWrData = Output(Vec(rfPorts, SInt(32.W)))

    // === 内存访问接口（LD/ST 指令）===
    val memReq = Output(Valid(new Bundle {
      val warpId = UInt(log2Ceil(numWarps).W)
      val isLoad = Bool()
      val addr   = UInt(GpuParams.GlobalAddrW.W)
      val rdReg  = UInt(4.W)
    }))
    val memWdata = Output(Vec(warpWidth, SInt(32.W)))

    // === Warp 上下文更新 ===
    val warpUpdate = Output(Vec(numWarps, new Bundle {
      val valid     = Bool()
      val pcInc     = Bool()  // PC++
      val setState  = Valid(WarpState())
      val setMemWait = Valid(UInt(8.W))
      val setMemRd   = Valid(UInt(4.W))
    }))

    // Per-scheduler pipeline backpressure. Each scheduler/sub-partition has
    // its own issue lane, so independent lanes can issue in the same cycle.
    val issueBusy = Output(Vec(numSchedulers, Vec(2, Bool())))
  })

  // === 默认值 ===
  for (i <- 0 until numWarps) {
    io.imemAddr(i) := io.warpPC(i)
    io.warpUpdate(i).valid := false.B
    io.warpUpdate(i).pcInc := false.B
    io.warpUpdate(i).setState.valid := false.B
    io.warpUpdate(i).setState.bits := WarpState.Ready
    io.warpUpdate(i).setMemWait.valid := false.B
    io.warpUpdate(i).setMemWait.bits := 0.U
    io.warpUpdate(i).setMemRd.valid := false.B
    io.warpUpdate(i).setMemRd.bits := 0.U
  }

  for (i <- 0 until rfPorts) {
    io.regRdAddr(i).valid := false.B
    io.regRdAddr(i).warpId := 0.U
    io.regRdAddr(i).laneId := 0.U
    io.regRdAddr(i).rs1 := 0.U
    io.regRdAddr(i).rs2 := 0.U
    io.regRdAddr(i).rs3 := 0.U

    io.regWrAddr(i).valid := false.B
    io.regWrAddr(i).warpId := 0.U
    io.regWrAddr(i).laneId := 0.U
    io.regWrAddr(i).rd := 0.U
    io.regWrData(i) := 0.S
  }

  for (i <- 0 until numCores) {
    io.coreValid(i) := false.B
    io.coreOp(i) := 0.U
    io.coreRs1(i) := 0.S
    io.coreRs2(i) := 0.S
    io.coreRs3(i) := 0.S
    io.coreWarpId(i) := 0.U
    io.coreLaneId(i) := 0.U

    io.sfuValid(i) := false.B
    io.sfuOp(i) := 0.U
    io.sfuRs1(i) := 0.S
    io.sfuWarpId(i) := 0.U
    io.sfuLaneId(i) := 0.U
  }

  io.memReq.valid := false.B
  io.memReq.bits.warpId := 0.U
  io.memReq.bits.isLoad := false.B
  io.memReq.bits.addr := 0.U
  io.memReq.bits.rdReg := 0.U
  io.memWdata := VecInit.fill(warpWidth)(0.S)

  val laneW = log2Ceil(warpWidth)
  val warpW = log2Ceil(numWarps)

  val aluRfValidReg = RegInit(VecInit(Seq.fill(numSchedulers)(false.B)))
  val aluRfLaneValidReg = RegInit(VecInit(Seq.fill(numCores)(false.B)))
  val aluRfOpReg = RegInit(VecInit(Seq.fill(numCores)(0.U(4.W))))
  val aluRfRdReg = RegInit(VecInit(Seq.fill(numCores)(0.U(4.W))))
  val aluRfWarpIdReg = RegInit(VecInit(Seq.fill(numCores)(0.U(warpW.W))))
  val aluRfLaneIdReg = RegInit(VecInit(Seq.fill(numCores)(0.U(laneW.W))))
  val aluRfImmReg = RegInit(VecInit(Seq.fill(numCores)(0.U(12.W))))

  val sfuRfValidReg = RegInit(VecInit(Seq.fill(numSchedulers)(false.B)))
  val sfuRfLaneValidReg = RegInit(VecInit(Seq.fill(numCores)(false.B)))
  val sfuRfRdReg = RegInit(VecInit(Seq.fill(numCores)(0.U(4.W))))
  val sfuRfWarpIdReg = RegInit(VecInit(Seq.fill(numCores)(0.U(warpW.W))))
  val sfuRfLaneIdReg = RegInit(VecInit(Seq.fill(numCores)(0.U(laneW.W))))

  val aluWbBusyReg = RegInit(VecInit(Seq.fill(numCores)(false.B)))
  val aluWbRdReg = RegInit(VecInit(Seq.fill(numCores)(0.U(4.W))))
  val aluWbWarpIdReg = RegInit(VecInit(Seq.fill(numCores)(0.U(warpW.W))))
  val aluWbLaneIdReg = RegInit(VecInit(Seq.fill(numCores)(0.U(laneW.W))))

  val sfuWbBusyReg = RegInit(VecInit(Seq.fill(numCores)(false.B)))
  val sfuWbRdReg = RegInit(VecInit(Seq.fill(numCores)(0.U(4.W))))
  val sfuWbWarpIdReg = RegInit(VecInit(Seq.fill(numCores)(0.U(warpW.W))))
  val sfuWbLaneIdReg = RegInit(VecInit(Seq.fill(numCores)(0.U(laneW.W))))

  val aluDecodeValidReg = RegInit(VecInit(Seq.fill(numSchedulers)(false.B)))
  val aluDecodeWarpReg = RegInit(VecInit(Seq.fill(numSchedulers)(0.U(warpW.W))))
  val aluDecodeInstrReg = RegInit(VecInit(Seq.fill(numSchedulers)(0.U(GpuParams.InstrWidth.W))))

  val sfuDecodeValidReg = RegInit(VecInit(Seq.fill(numSchedulers)(false.B)))
  val sfuDecodeWarpReg = RegInit(VecInit(Seq.fill(numSchedulers)(0.U(warpW.W))))
  val sfuDecodeInstrReg = RegInit(VecInit(Seq.fill(numSchedulers)(0.U(GpuParams.InstrWidth.W))))

  val memPendingValid = RegInit(VecInit(Seq.fill(numSchedulers)(false.B)))
  val memPendingWarpId = RegInit(VecInit(Seq.fill(numSchedulers)(0.U(warpW.W))))
  val memPendingIsLoad = RegInit(VecInit(Seq.fill(numSchedulers)(false.B)))
  val memPendingAddr = RegInit(VecInit(Seq.fill(numSchedulers)(0.U(GpuParams.GlobalAddrW.W))))
  val memPendingRd = RegInit(VecInit(Seq.fill(numSchedulers)(0.U(4.W))))
  val memPendingWdata = RegInit(VecInit(Seq.fill(numSchedulers)(
    VecInit(Seq.fill(warpWidth)(0.S(32.W)))
  )))

  val aluBusy = Wire(Vec(numSchedulers, Bool()))
  val sfuBusy = Wire(Vec(numSchedulers, Bool()))
  for (s <- 0 until numSchedulers) {
    val groupAluWbBusy = VecInit((0 until warpWidth).map { lane =>
      val coreId = s * warpWidth + lane
      if (coreId < numCores) aluWbBusyReg(coreId) else false.B
    }).asUInt.orR

    val groupSfuWbBusy = VecInit((0 until warpWidth).map { lane =>
      val coreId = s * warpWidth + lane
      if (coreId < numCores) sfuWbBusyReg(coreId) else false.B
    }).asUInt.orR

    aluBusy(s) := aluDecodeValidReg(s) || aluRfValidReg(s) || memPendingValid(s) || groupAluWbBusy
    sfuBusy(s) := sfuDecodeValidReg(s) || sfuRfValidReg(s) || groupSfuWbBusy
    io.issueBusy(s)(0) := aluBusy(s)
    io.issueBusy(s)(1) := sfuBusy(s)
  }

  val warpInFlight = Wire(Vec(numWarps, Bool()))
  for (w <- 0 until numWarps) {
    val warpId = w.U(warpW.W)
    val decodeBusy = VecInit((0 until numSchedulers).map { s =>
      (aluDecodeValidReg(s) && aluDecodeWarpReg(s) === warpId) ||
        (sfuDecodeValidReg(s) && sfuDecodeWarpReg(s) === warpId)
    }).asUInt.orR
    val rfBusy = VecInit((0 until numSchedulers).map { s =>
      val baseCore = s * warpWidth
      val aluRfBusy =
        if (baseCore < numCores) aluRfValidReg(s) && aluRfWarpIdReg(baseCore) === warpId else false.B
      val sfuRfBusy =
        if (baseCore < numCores) sfuRfValidReg(s) && sfuRfWarpIdReg(baseCore) === warpId else false.B
      aluRfBusy || sfuRfBusy
    }).asUInt.orR
    val memBusy = VecInit((0 until numSchedulers).map { s =>
      memPendingValid(s) && memPendingWarpId(s) === warpId
    }).asUInt.orR
    val wbBusy = VecInit((0 until numCores).map { i =>
      (aluWbBusyReg(i) && aluWbWarpIdReg(i) === warpId) ||
        (sfuWbBusyReg(i) && sfuWbWarpIdReg(i) === warpId)
    }).asUInt.orR
    warpInFlight(w) := decodeBusy || rfBusy || memBusy || wbBusy
  }

  // === S0: 每个 scheduler 可以同时选择一个 ALU/MEM/control warp 和一个 SFU warp ===
  for (s <- 0 until numSchedulers) {
    val candidateAlu = Wire(Vec(localWarps, Bool()))
    val candidateSfu = Wire(Vec(localWarps, Bool()))

    for (c <- 0 until localWarps) {
      val valid = io.selectedWarp(s)(c).valid
      val warpId = io.selectedWarp(s)(c).bits
      val op = io.imemData(warpId)(31, 28)
      val ready = io.warpState(warpId) === WarpState.Ready && !warpInFlight(warpId)
      candidateAlu(c) := valid && ready && op =/= GpuOpcode.EXP
      candidateSfu(c) := valid && ready && op === GpuOpcode.EXP
    }

    val aluSelectValid = candidateAlu.asUInt.orR && !aluBusy(s)
    val aluSelectOH = PriorityEncoderOH(candidateAlu)
    val aluWarp = Mux1H(aluSelectOH, (0 until localWarps).map(c => io.selectedWarp(s)(c).bits))
    when(aluSelectValid) {
      aluDecodeValidReg(s) := true.B
      aluDecodeWarpReg(s) := aluWarp
      aluDecodeInstrReg(s) := io.imemData(aluWarp)
    }

    val sfuSelectValid = candidateSfu.asUInt.orR && !sfuBusy(s)
    val sfuSelectOH = PriorityEncoderOH(candidateSfu)
    val sfuWarp = Mux1H(sfuSelectOH, (0 until localWarps).map(c => io.selectedWarp(s)(c).bits))
    when(sfuSelectValid) {
      sfuDecodeValidReg(s) := true.B
      sfuDecodeWarpReg(s) := sfuWarp
      sfuDecodeInstrReg(s) := io.imemData(sfuWarp)
    }
  }

  val aluDecodeOp = Wire(Vec(numSchedulers, UInt(4.W)))
  val aluDecodeRd = Wire(Vec(numSchedulers, UInt(4.W)))
  val aluDecodeRs1 = Wire(Vec(numSchedulers, UInt(4.W)))
  val aluDecodeRs2 = Wire(Vec(numSchedulers, UInt(4.W)))
  val aluDecodeRs3 = Wire(Vec(numSchedulers, UInt(4.W)))
  val aluDecodeImm = Wire(Vec(numSchedulers, UInt(12.W)))
  val aluNeedsRegisterRead = Wire(Vec(numSchedulers, Bool()))

  val sfuDecodeRd = Wire(Vec(numSchedulers, UInt(4.W)))
  val sfuDecodeRs1 = Wire(Vec(numSchedulers, UInt(4.W)))

  for (s <- 0 until numSchedulers) {
    aluDecodeOp(s) := aluDecodeInstrReg(s)(31, 28)
    aluDecodeRd(s) := aluDecodeInstrReg(s)(27, 24)
    aluDecodeRs1(s) := aluDecodeInstrReg(s)(23, 20)
    aluDecodeRs2(s) := aluDecodeInstrReg(s)(19, 16)
    aluDecodeRs3(s) := aluDecodeInstrReg(s)(15, 12)
    aluDecodeImm(s) := aluDecodeInstrReg(s)(11, 0)

    aluNeedsRegisterRead(s) :=
      aluDecodeOp(s) === GpuOpcode.ADD || aluDecodeOp(s) === GpuOpcode.MUL ||
        aluDecodeOp(s) === GpuOpcode.MAD || aluDecodeOp(s) === GpuOpcode.LD ||
        aluDecodeOp(s) === GpuOpcode.ST

    sfuDecodeRd(s) := sfuDecodeInstrReg(s)(27, 24)
    sfuDecodeRs1(s) := sfuDecodeInstrReg(s)(23, 20)
  }

  for (s <- 0 until numSchedulers) {
    when(aluRfValidReg(s)) {
      aluRfValidReg(s) := false.B
      for (lane <- 0 until warpWidth) {
        val coreId = s * warpWidth + lane
        if (coreId < numCores) {
          aluRfLaneValidReg(coreId) := false.B
        }
      }
    }

    when(sfuRfValidReg(s)) {
      sfuRfValidReg(s) := false.B
      for (lane <- 0 until warpWidth) {
        val coreId = s * warpWidth + lane
        if (coreId < numCores) {
          sfuRfLaneValidReg(coreId) := false.B
        }
      }
    }
  }

  // === S1: ALU/MEM/control decode lane ===
  for (s <- 0 until numSchedulers) {
    when(aluDecodeValidReg(s)) {
      aluDecodeValidReg(s) := false.B

      switch(aluDecodeOp(s)) {
        is(GpuOpcode.NOP) {
          io.warpUpdate(aluDecodeWarpReg(s)).valid := true.B
          io.warpUpdate(aluDecodeWarpReg(s)).pcInc := true.B
        }
        is(GpuOpcode.HALT) {
          io.warpUpdate(aluDecodeWarpReg(s)).valid := true.B
          io.warpUpdate(aluDecodeWarpReg(s)).setState.valid := true.B
          io.warpUpdate(aluDecodeWarpReg(s)).setState.bits := WarpState.Halted
        }
      }
    }

    when(aluDecodeValidReg(s) && aluNeedsRegisterRead(s)) {
      aluRfValidReg(s) := true.B
      for (lane <- 0 until warpWidth) {
        val coreId = s * warpWidth + lane
        val rfPort = aluPortBase + coreId
        if (coreId < numCores) {
          val laneActive = aluDecodeOp(s) =/= GpuOpcode.LD || lane.U === 0.U

          io.regRdAddr(rfPort).valid := laneActive
          io.regRdAddr(rfPort).warpId := aluDecodeWarpReg(s)
          io.regRdAddr(rfPort).laneId := lane.U
          io.regRdAddr(rfPort).rs1 := aluDecodeRs1(s)
          io.regRdAddr(rfPort).rs2 := aluDecodeRs2(s)
          io.regRdAddr(rfPort).rs3 := aluDecodeRs3(s)

          aluRfLaneValidReg(coreId) := laneActive
          aluRfOpReg(coreId) := aluDecodeOp(s)
          aluRfRdReg(coreId) := aluDecodeRd(s)
          aluRfWarpIdReg(coreId) := aluDecodeWarpReg(s)
          aluRfLaneIdReg(coreId) := lane.U
          aluRfImmReg(coreId) := aluDecodeImm(s)
        }
      }

      when(
        aluDecodeOp(s) === GpuOpcode.ADD || aluDecodeOp(s) === GpuOpcode.MUL ||
          aluDecodeOp(s) === GpuOpcode.MAD
      ) {
        io.warpUpdate(aluDecodeWarpReg(s)).valid := true.B
        io.warpUpdate(aluDecodeWarpReg(s)).pcInc := true.B
      }
    }
  }

  // === S1: SFU decode lane ===
  for (s <- 0 until numSchedulers) {
    when(sfuDecodeValidReg(s)) {
      sfuDecodeValidReg(s) := false.B
      sfuRfValidReg(s) := true.B

      for (lane <- 0 until warpWidth) {
        val coreId = s * warpWidth + lane
        val rfPort = sfuPortBase + coreId
        if (coreId < numCores) {
          io.regRdAddr(rfPort).valid := true.B
          io.regRdAddr(rfPort).warpId := sfuDecodeWarpReg(s)
          io.regRdAddr(rfPort).laneId := lane.U
          io.regRdAddr(rfPort).rs1 := sfuDecodeRs1(s)
          io.regRdAddr(rfPort).rs2 := 0.U
          io.regRdAddr(rfPort).rs3 := 0.U

          sfuRfLaneValidReg(coreId) := true.B
          sfuRfRdReg(coreId) := sfuDecodeRd(s)
          sfuRfWarpIdReg(coreId) := sfuDecodeWarpReg(s)
          sfuRfLaneIdReg(coreId) := lane.U
        }
      }

      io.warpUpdate(sfuDecodeWarpReg(s)).valid := true.B
      io.warpUpdate(sfuDecodeWarpReg(s)).pcInc := true.B
    }
  }

  val memCandValid = Wire(Vec(numSchedulers, Bool()))
  val memCandWarpId = Wire(Vec(numSchedulers, UInt(warpW.W)))
  val memCandIsLoad = Wire(Vec(numSchedulers, Bool()))
  val memCandAddr = Wire(Vec(numSchedulers, UInt(GpuParams.GlobalAddrW.W)))
  val memCandRd = Wire(Vec(numSchedulers, UInt(4.W)))
  val memCandWdata = Wire(Vec(numSchedulers, Vec(warpWidth, SInt(32.W))))

  for (s <- 0 until numSchedulers) {
    memCandValid(s) := false.B
    memCandWarpId(s) := 0.U
    memCandIsLoad(s) := false.B
    memCandAddr(s) := 0.U
    memCandRd(s) := 0.U
    memCandWdata(s) := VecInit(Seq.fill(warpWidth)(0.S(32.W)))
  }

  // === S2/S3: 使用上一拍寄存器读响应，发往执行单元或内存接口 ===
  for (s <- 0 until numSchedulers) {
    when(aluRfValidReg(s)) {
      val baseCore = s * warpWidth
      val baseRfPort = aluPortBase + baseCore
      val baseOp = aluRfOpReg(baseCore)

      when(baseOp === GpuOpcode.LD) {
        val addr = io.regRdData(baseRfPort).rs1.asUInt + aluRfImmReg(baseCore)
        memCandValid(s) := true.B
        memCandWarpId(s) := aluRfWarpIdReg(baseCore)
        memCandIsLoad(s) := true.B
        memCandAddr(s) := addr(GpuParams.GlobalAddrW - 1, 0)
        memCandRd(s) := aluRfRdReg(baseCore)
      }.elsewhen(baseOp === GpuOpcode.ST) {
        val addr = io.regRdData(baseRfPort).rs1.asUInt + aluRfImmReg(baseCore)
        memCandValid(s) := true.B
        memCandWarpId(s) := aluRfWarpIdReg(baseCore)
        memCandIsLoad(s) := false.B
        memCandAddr(s) := addr(GpuParams.GlobalAddrW - 1, 0)
        memCandRd(s) := aluRfRdReg(baseCore)
        for (lane <- 0 until warpWidth) {
          val coreId = baseCore + lane
          val rfPort = aluPortBase + coreId
          if (coreId < numCores) {
            memCandWdata(s)(lane) := io.regRdData(rfPort).rs2
          }
        }
      }.otherwise {
        for (lane <- 0 until warpWidth) {
          val coreId = baseCore + lane
          val rfPort = aluPortBase + coreId
          if (coreId < numCores) {
            val op = aluRfOpReg(coreId)
            val isCompute =
              op === GpuOpcode.ADD || op === GpuOpcode.MUL ||
                op === GpuOpcode.MAD

            when(aluRfLaneValidReg(coreId) && isCompute) {
              io.coreValid(coreId) := true.B
              io.coreOp(coreId) := op
              io.coreRs1(coreId) := io.regRdData(rfPort).rs1
              io.coreRs2(coreId) := io.regRdData(rfPort).rs2
              io.coreRs3(coreId) := io.regRdData(rfPort).rs3
              io.coreWarpId(coreId) := aluRfWarpIdReg(coreId)
              io.coreLaneId(coreId) := aluRfLaneIdReg(coreId)

              aluWbBusyReg(coreId) := true.B
              aluWbRdReg(coreId) := aluRfRdReg(coreId)
              aluWbWarpIdReg(coreId) := aluRfWarpIdReg(coreId)
              aluWbLaneIdReg(coreId) := aluRfLaneIdReg(coreId)
            }
          }
        }
      }
    }

    when(sfuRfValidReg(s)) {
      val baseCore = s * warpWidth
      for (lane <- 0 until warpWidth) {
        val coreId = baseCore + lane
        val rfPort = sfuPortBase + coreId
        if (coreId < numCores) {
          when(sfuRfLaneValidReg(coreId)) {
            io.sfuValid(coreId) := true.B
            io.sfuOp(coreId) := GpuOpcode.EXP
            io.sfuRs1(coreId) := io.regRdData(rfPort).rs1
            io.sfuWarpId(coreId) := sfuRfWarpIdReg(coreId)
            io.sfuLaneId(coreId) := sfuRfLaneIdReg(coreId)

            sfuWbBusyReg(coreId) := true.B
            sfuWbRdReg(coreId) := sfuRfRdReg(coreId)
            sfuWbWarpIdReg(coreId) := sfuRfWarpIdReg(coreId)
            sfuWbLaneIdReg(coreId) := sfuRfLaneIdReg(coreId)
          }
        }
      }
    }
  }

  // === 单端口内存请求仲裁 ===
  val memReqValidVec = Wire(Vec(numSchedulers, Bool()))
  val memReqWarpIdVec = Wire(Vec(numSchedulers, UInt(warpW.W)))
  val memReqIsLoadVec = Wire(Vec(numSchedulers, Bool()))
  val memReqAddrVec = Wire(Vec(numSchedulers, UInt(GpuParams.GlobalAddrW.W)))
  val memReqRdVec = Wire(Vec(numSchedulers, UInt(4.W)))
  val memReqWdataVec = Wire(Vec(numSchedulers, Vec(warpWidth, SInt(32.W))))

  for (s <- 0 until numSchedulers) {
    memReqValidVec(s) := memPendingValid(s) || memCandValid(s)
    memReqWarpIdVec(s) := Mux(memPendingValid(s), memPendingWarpId(s), memCandWarpId(s))
    memReqIsLoadVec(s) := Mux(memPendingValid(s), memPendingIsLoad(s), memCandIsLoad(s))
    memReqAddrVec(s) := Mux(memPendingValid(s), memPendingAddr(s), memCandAddr(s))
    memReqRdVec(s) := Mux(memPendingValid(s), memPendingRd(s), memCandRd(s))
    memReqWdataVec(s) := Mux(memPendingValid(s), memPendingWdata(s), memCandWdata(s))
  }

  val memReqFire = memReqValidVec.asUInt.orR
  val memReqSelOH = PriorityEncoderOH(memReqValidVec)
  io.memReq.valid := memReqFire
  io.memReq.bits.warpId := Mux1H(memReqSelOH, memReqWarpIdVec)
  io.memReq.bits.isLoad := Mux1H(memReqSelOH, memReqIsLoadVec)
  io.memReq.bits.addr := Mux1H(memReqSelOH, memReqAddrVec)
  io.memReq.bits.rdReg := Mux1H(memReqSelOH, memReqRdVec)
  io.memWdata := Mux1H(memReqSelOH, memReqWdataVec)

  for (s <- 0 until numSchedulers) {
    val memChosen = memReqFire && memReqSelOH(s)
    when(memChosen) {
      when(memReqIsLoadVec(s)) {
        io.warpUpdate(memReqWarpIdVec(s)).valid := true.B
        io.warpUpdate(memReqWarpIdVec(s)).setState.valid := true.B
        io.warpUpdate(memReqWarpIdVec(s)).setState.bits := WarpState.Stalled
        io.warpUpdate(memReqWarpIdVec(s)).setMemWait.valid := true.B
        io.warpUpdate(memReqWarpIdVec(s)).setMemWait.bits := gmemLatency.U
        io.warpUpdate(memReqWarpIdVec(s)).setMemRd.valid := true.B
        io.warpUpdate(memReqWarpIdVec(s)).setMemRd.bits := memReqRdVec(s)
      }.otherwise {
        io.warpUpdate(memReqWarpIdVec(s)).valid := true.B
        io.warpUpdate(memReqWarpIdVec(s)).pcInc := true.B
      }

      when(memPendingValid(s)) {
        memPendingValid(s) := false.B
      }
    }

    when(memCandValid(s) && !memChosen) {
      memPendingValid(s) := true.B
      memPendingWarpId(s) := memCandWarpId(s)
      memPendingIsLoad(s) := memCandIsLoad(s)
      memPendingAddr(s) := memCandAddr(s)
      memPendingRd(s) := memCandRd(s)
      memPendingWdata(s) := memCandWdata(s)
    }
  }
  // === S4: 执行单元完成后写回寄存器文件 ===
  for (i <- 0 until numCores) {
    val aluWrPort = aluPortBase + i
    val sfuWrPort = sfuPortBase + i

    when(io.coreDone(i) && aluWbBusyReg(i)) {
      io.regWrAddr(aluWrPort).valid := true.B
      io.regWrAddr(aluWrPort).warpId := aluWbWarpIdReg(i)
      io.regWrAddr(aluWrPort).laneId := aluWbLaneIdReg(i)
      io.regWrAddr(aluWrPort).rd := aluWbRdReg(i)
      io.regWrData(aluWrPort) := io.coreRd(i)
      aluWbBusyReg(i) := false.B
    }

    when(io.sfuDone(i) && sfuWbBusyReg(i)) {
      io.regWrAddr(sfuWrPort).valid := true.B
      io.regWrAddr(sfuWrPort).warpId := sfuWbWarpIdReg(i)
      io.regWrAddr(sfuWrPort).laneId := sfuWbLaneIdReg(i)
      io.regWrAddr(sfuWrPort).rd := sfuWbRdReg(i)
      io.regWrData(sfuWrPort) := io.sfuRd(i)
      sfuWbBusyReg(i) := false.B
    }
  }
}
