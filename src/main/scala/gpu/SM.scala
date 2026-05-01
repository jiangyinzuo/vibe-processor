package gpu

import chisel3._
import chisel3.util._

/** Streaming Multiprocessor with Dual Warp Schedulers (双调度器版本)
  *
  * 架构改进：
  *   - 2 个独立的 Warp Scheduler，每个管理一半的 Warp
  *   - 每周期可以并行执行 2 个 Warp（如果资源允许）
  *   - 资源分区：GlobalMem 和 SharedMem 支持仲裁
  *
  * 性能提升：
  *   - 理论吞吐量：2× 单调度器
  *   - 实际提升取决于资源冲突情况
  */
class SM(
    numWarps:    Int = GpuParams.NumWarps,
    warpWidth:   Int = GpuParams.WarpWidth,
    dw:          Int = GpuParams.DataWidth,
    memLatency:  Int = 1
) extends Module {
  val io = IO(new Bundle {
    val start     = Input(Bool())
    val allHalted = Output(Bool())
    val imemAddr  = Output(Vec(numWarps, UInt(8.W)))
    val imemData  = Input(Vec(numWarps, UInt(GpuParams.InstrWidth.W)))
    // Global memory (shared Mem, combinational read)
    val gmemEn    = Output(Bool())
    val gmemWe    = Output(Bool())
    val gmemAddr  = Output(UInt(GpuParams.GlobalAddrW.W))
    val gmemWdata = Output(Vec(warpWidth, SInt(dw.W)))
    val gmemRdata = Input(Vec(warpWidth, SInt(dw.W)))
    // Debug
    val dbgGrant  = Output(Vec(numWarps, Bool()))
  })

  // === 双调度器架构 ===
  val numSchedulers = 2
  val warpsPerScheduler = numWarps / numSchedulers
  require(numWarps % numSchedulers == 0, "numWarps must be divisible by numSchedulers")

  // 2 个独立的 Warp Scheduler
  val schedulers = Array.fill(numSchedulers)(Module(new WarpScheduler(warpsPerScheduler)))

  // 所有 Warp
  val warps = Array.fill(numWarps)(Module(new Warp(warpWidth, memLatency = memLatency)))

  // SharedMem
  val sharedMem = SyncReadMem(GpuParams.SharedDepth, Vec(warpWidth, SInt(dw.W)))

  // === 将 Warp 分配给调度器 ===
  // Scheduler 0: Warp 0, 1
  // Scheduler 1: Warp 2, 3
  for (s <- 0 until numSchedulers) {
    for (w <- 0 until warpsPerScheduler) {
      val warpId = s * warpsPerScheduler + w
      // === 协作式调度的关键连接 ===
      // warpHalted 信号包含两部分：
      //   1. io.halted: Warp 已执行完毕（HALT 指令）
      //   2. io.busy: Warp 正在等待内存（主动让出时间片）
      // 调度器会跳过 halted 或 busy 的 Warp，选择其他活跃的 Warp
      // 这实现了协作式调度：Warp 通过 busy 信号主动告知调度器自己的状态
      schedulers(s).io.warpHalted(w) := warps(warpId).io.halted || warps(warpId).io.busy
    }
  }

  // === 合并调度器的 grant 信号 ===
  val combinedGrant = Wire(Vec(numWarps, Bool()))
  for (s <- 0 until numSchedulers) {
    for (w <- 0 until warpsPerScheduler) {
      val warpId = s * warpsPerScheduler + w
      combinedGrant(warpId) := schedulers(s).io.grant(w)
    }
  }
  io.dbgGrant := combinedGrant

  // === 所有 Warp 是否都已停止 ===
  io.allHalted := VecInit(warps.map(_.io.halted)).asUInt.andR

  // === 连接 Warp 的基本信号 ===
  val shmRdata = Wire(Vec(warpWidth, SInt(dw.W)))
  shmRdata := VecInit.fill(warpWidth)(0.S(dw.W))

  for (i <- 0 until numWarps) {
    warps(i).io.start    := io.start
    warps(i).io.enable   := combinedGrant(i)
    warps(i).io.instr    := io.imemData(i)
    warps(i).io.shmRdata := shmRdata
    warps(i).io.gmemRdata := io.gmemRdata
    io.imemAddr(i)       := warps(i).io.pc
  }

  // === GlobalMem 仲裁 ===
  // 如果两个 Warp 同时请求 GlobalMem，优先级：Scheduler 0 > Scheduler 1
  val gmemRequests = VecInit(warps.zipWithIndex.map { case (w, i) => w.io.gmemEn && combinedGrant(i) })
  val gmemGrantIdx = PriorityEncoder(gmemRequests.asUInt)
  val hasGmemRequest = gmemRequests.asUInt.orR

  io.gmemEn    := false.B
  io.gmemWe    := false.B
  io.gmemAddr  := 0.U
  io.gmemWdata := VecInit.fill(warpWidth)(0.S(dw.W))

  for (i <- 0 until numWarps) {
    when(hasGmemRequest && gmemGrantIdx === i.U) {
      io.gmemEn    := warps(i).io.gmemEn
      io.gmemWe    := warps(i).io.gmemWe
      io.gmemAddr  := warps(i).io.gmemAddr
      io.gmemWdata := warps(i).io.gmemWdata
    }
  }

  // === SharedMem 仲裁 ===
  // 如果两个 Warp 同时请求 SharedMem，优先级：Scheduler 0 > Scheduler 1
  val shmRequests = VecInit(warps.zipWithIndex.map { case (w, i) => w.io.shmEn && combinedGrant(i) })
  val shmGrantIdx = PriorityEncoder(shmRequests.asUInt)
  val hasShmRequest = shmRequests.asUInt.orR

  val shmAddr  = Wire(UInt(GpuParams.SharedAddrW.W))
  val shmEn    = Wire(Bool())
  val shmWe    = Wire(Bool())
  val shmWdata = Wire(Vec(warpWidth, SInt(dw.W)))

  shmAddr  := 0.U
  shmEn    := false.B
  shmWe    := false.B
  shmWdata := VecInit.fill(warpWidth)(0.S(dw.W))

  for (i <- 0 until numWarps) {
    when(hasShmRequest && shmGrantIdx === i.U) {
      shmEn    := warps(i).io.shmEn
      shmWe    := warps(i).io.shmWe
      shmAddr  := warps(i).io.shmAddr
      shmWdata := warps(i).io.shmWdata
    }
  }

  shmRdata := sharedMem.read(shmAddr)
  when(shmEn && shmWe) { sharedMem.write(shmAddr, shmWdata) }
}
