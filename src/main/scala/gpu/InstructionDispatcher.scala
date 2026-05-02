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
  val io = IO(new Bundle {
    // === 来自调度器的选中 Warp ===
    val selectedWarp = Input(Vec(numSchedulers, Valid(UInt(log2Ceil(numWarps).W))))

    // === Warp 上下文（只读）===
    val warpPC = Input(Vec(numWarps, UInt(8.W)))
    val warpState = Input(Vec(numWarps, WarpState()))

    // === 指令内存接口 ===
    val imemAddr = Output(Vec(numWarps, UInt(8.W)))
    val imemData = Input(Vec(numWarps, UInt(GpuParams.InstrWidth.W)))

    // === 到寄存器文件的读请求 ===
    val regRdAddr = Output(Vec(numCores, new Bundle {
      val valid  = Bool()
      val warpId = UInt(log2Ceil(numWarps).W)
      val laneId = UInt(log2Ceil(warpWidth).W)
      val rs1    = UInt(4.W)
      val rs2    = UInt(4.W)
      val rs3    = UInt(4.W)
    }))
    val regRdData = Input(Vec(numCores, new Bundle {
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

    // === 来自 CUDA Core 的结果 ===
    val coreDone   = Input(Vec(numCores, Bool()))
    val coreRd     = Input(Vec(numCores, SInt(32.W)))

    // === 到寄存器文件的写请求 ===
    val regWrAddr = Output(Vec(numCores, new Bundle {
      val valid  = Bool()
      val warpId = UInt(log2Ceil(numWarps).W)
      val laneId = UInt(log2Ceil(warpWidth).W)
      val rd     = UInt(4.W)
    }))
    val regWrData = Output(Vec(numCores, SInt(32.W)))

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

  for (i <- 0 until numCores) {
    io.regRdAddr(i).valid := false.B
    io.regRdAddr(i).warpId := 0.U
    io.regRdAddr(i).laneId := 0.U
    io.regRdAddr(i).rs1 := 0.U
    io.regRdAddr(i).rs2 := 0.U
    io.regRdAddr(i).rs3 := 0.U

    io.coreValid(i) := false.B
    io.coreOp(i) := 0.U
    io.coreRs1(i) := 0.S
    io.coreRs2(i) := 0.S
    io.coreRs3(i) := 0.S
    io.coreWarpId(i) := 0.U
    io.coreLaneId(i) := 0.U

    io.regWrAddr(i).valid := false.B
    io.regWrAddr(i).warpId := 0.U
    io.regWrAddr(i).laneId := 0.U
    io.regWrAddr(i).rd := 0.U
    io.regWrData(i) := 0.S
  }

  io.memReq.valid := false.B
  io.memReq.bits.warpId := 0.U
  io.memReq.bits.isLoad := false.B
  io.memReq.bits.addr := 0.U
  io.memReq.bits.rdReg := 0.U
  io.memWdata := VecInit.fill(warpWidth)(0.S)

  // === 分发逻辑 ===
  // 为每个调度器选中的 Warp 分配 CUDA Core
  var coreIdx = 0
  for (s <- 0 until numSchedulers) {
    when(io.selectedWarp(s).valid) {
      val warpId = io.selectedWarp(s).bits
      val instr = io.imemData(warpId)
      val op = instr(31, 28)
      val rd = instr(27, 24)
      val rs1 = instr(23, 20)
      val rs2 = instr(19, 16)
      val rs3 = instr(15, 12)
      val imm12 = instr(11, 0)

      // 根据指令类型处理
      switch(op) {
        is(GpuOpcode.NOP) {
          // NOP: 只更新 PC
          io.warpUpdate(warpId).valid := true.B
          io.warpUpdate(warpId).pcInc := true.B
        }
        is(GpuOpcode.HALT) {
          // HALT: 设置状态为 Halted
          io.warpUpdate(warpId).valid := true.B
          io.warpUpdate(warpId).setState.valid := true.B
          io.warpUpdate(warpId).setState.bits := WarpState.Halted
        }
        is(GpuOpcode.ADD, GpuOpcode.MUL, GpuOpcode.MAD) {
          // 算术指令: 分发到 CUDA Core
          for (lane <- 0 until warpWidth) {
            val coreId = coreIdx + lane
            if (coreId < numCores) {
              // 读寄存器
              io.regRdAddr(coreId).valid := true.B
              io.regRdAddr(coreId).warpId := warpId
              io.regRdAddr(coreId).laneId := lane.U
              io.regRdAddr(coreId).rs1 := rs1
              io.regRdAddr(coreId).rs2 := rs2
              io.regRdAddr(coreId).rs3 := rs3

              // 分发到 CUDA Core
              io.coreValid(coreId) := true.B
              io.coreOp(coreId) := op
              io.coreRs1(coreId) := io.regRdData(coreId).rs1
              io.coreRs2(coreId) := io.regRdData(coreId).rs2
              io.coreRs3(coreId) := io.regRdData(coreId).rs3
              io.coreWarpId(coreId) := warpId
              io.coreLaneId(coreId) := lane.U
            }
          }
          io.warpUpdate(warpId).valid := true.B
          io.warpUpdate(warpId).pcInc := true.B
        }
        is(GpuOpcode.LD) {
          // LOAD: 发起内存请求，设置 Warp 为 Stalled
          // 先读取 rs1 寄存器来计算地址
          io.regRdAddr(coreIdx).valid := true.B
          io.regRdAddr(coreIdx).warpId := warpId
          io.regRdAddr(coreIdx).laneId := 0.U  // 只需要 lane 0 的地址
          io.regRdAddr(coreIdx).rs1 := rs1

          val addr = io.regRdData(coreIdx).rs1.asUInt + imm12
          io.memReq.valid := true.B
          io.memReq.bits.warpId := warpId
          io.memReq.bits.isLoad := true.B
          io.memReq.bits.addr := addr(GpuParams.GlobalAddrW - 1, 0)
          io.memReq.bits.rdReg := rd

          io.warpUpdate(warpId).valid := true.B
          io.warpUpdate(warpId).setState.valid := true.B
          io.warpUpdate(warpId).setState.bits := WarpState.Stalled
          io.warpUpdate(warpId).setMemWait.valid := true.B
          io.warpUpdate(warpId).setMemWait.bits := gmemLatency.U  // 等待 gmemLatency 个周期
          io.warpUpdate(warpId).setMemRd.valid := true.B
          io.warpUpdate(warpId).setMemRd.bits := rd
        }
        is(GpuOpcode.ST) {
          // STORE: 发起内存写请求
          // 先读取 rs1 寄存器来计算地址
          io.regRdAddr(coreIdx).valid := true.B
          io.regRdAddr(coreIdx).warpId := warpId
          io.regRdAddr(coreIdx).laneId := 0.U
          io.regRdAddr(coreIdx).rs1 := rs1

          val addr = io.regRdData(coreIdx).rs1.asUInt + imm12
          io.memReq.valid := true.B
          io.memReq.bits.warpId := warpId
          io.memReq.bits.isLoad := false.B
          io.memReq.bits.addr := addr(GpuParams.GlobalAddrW - 1, 0)

          // 读取所有 lane 的 rs2 作为写数据
          for (lane <- 0 until warpWidth) {
            io.regRdAddr(coreIdx + lane).valid := true.B
            io.regRdAddr(coreIdx + lane).warpId := warpId
            io.regRdAddr(coreIdx + lane).laneId := lane.U
            io.regRdAddr(coreIdx + lane).rs2 := rs2
            io.memWdata(lane) := io.regRdData(coreIdx + lane).rs2
          }

          io.warpUpdate(warpId).valid := true.B
          io.warpUpdate(warpId).pcInc := true.B
        }
      }

      coreIdx += warpWidth
    }
  }

  // === 结果写回 ===
  // CUDA Core 完成计算后，写回寄存器文件
  // 需要保存指令的 rd 字段（目标寄存器）
  val wbRd = Wire(Vec(numCores, UInt(4.W)))
  val wbWarpId = Wire(Vec(numCores, UInt(log2Ceil(numWarps).W)))
  val wbLaneId = Wire(Vec(numCores, UInt(log2Ceil(warpWidth).W)))

  for (i <- 0 until numCores) {
    wbRd(i) := 0.U
    wbWarpId(i) := 0.U
    wbLaneId(i) := 0.U
  }

  // 在分发时记录 rd（重新遍历，与分发逻辑保持一致）
  var wbCoreIdx = 0
  for (s <- 0 until numSchedulers) {
    when(io.selectedWarp(s).valid) {
      val warpId = io.selectedWarp(s).bits
      val instr = io.imemData(warpId)
      val op = instr(31, 28)
      val rd = instr(27, 24)

      when(op === GpuOpcode.ADD || op === GpuOpcode.MUL || op === GpuOpcode.MAD) {
        for (lane <- 0 until warpWidth) {
          val coreId = wbCoreIdx + lane
          if (coreId < numCores) {
            wbRd(coreId) := rd
            wbWarpId(coreId) := warpId
            wbLaneId(coreId) := lane.U
          }
        }
      }
      wbCoreIdx += warpWidth
    }
  }

  val wbRdReg = RegNext(wbRd)
  val wbWarpIdReg = RegNext(wbWarpId)
  val wbLaneIdReg = RegNext(wbLaneId)

  for (i <- 0 until numCores) {
    when(io.coreDone(i)) {
      io.regWrAddr(i).valid := true.B
      io.regWrAddr(i).warpId := wbWarpIdReg(i)
      io.regWrAddr(i).laneId := wbLaneIdReg(i)
      io.regWrAddr(i).rd := wbRdReg(i)
      io.regWrData(i) := io.coreRd(i)
    }
  }
}
