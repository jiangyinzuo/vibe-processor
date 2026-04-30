package gpu

import chisel3._
import chisel3.util._

/** Streaming Multiprocessor: integrates Warps, Scheduler, SharedMem.
  *
  * Memory latency is handled inside each Warp (internal counter).
  * SM just muxes the active warp's memory requests to the shared ports.
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

  val scheduler = Module(new WarpScheduler(numWarps))
  val warps     = Array.fill(numWarps)(Module(new Warp(warpWidth, memLatency = memLatency)))
  val sharedMem = SyncReadMem(GpuParams.SharedDepth, Vec(warpWidth, SInt(dw.W)))

  for (i <- 0 until numWarps) {
    // Don't schedule halted or memory-waiting warps
    scheduler.io.warpHalted(i) := warps(i).io.halted || warps(i).io.busy
  }
  io.allHalted := VecInit(warps.map(_.io.halted)).asUInt.andR
  io.dbgGrant  := scheduler.io.grant

  val shmRdata = Wire(Vec(warpWidth, SInt(dw.W)))
  shmRdata := VecInit.fill(warpWidth)(0.S(dw.W))

  io.gmemEn    := false.B
  io.gmemWe    := false.B
  io.gmemAddr  := 0.U
  io.gmemWdata := VecInit.fill(warpWidth)(0.S(dw.W))

  for (i <- 0 until numWarps) {
    warps(i).io.start    := io.start
    warps(i).io.enable   := scheduler.io.grant(i)
    warps(i).io.instr    := io.imemData(i)
    warps(i).io.shmRdata := shmRdata
    warps(i).io.gmemRdata := io.gmemRdata
    io.imemAddr(i)       := warps(i).io.pc

    when(scheduler.io.grant(i)) {
      io.gmemEn    := warps(i).io.gmemEn
      io.gmemWe    := warps(i).io.gmemWe
      io.gmemAddr  := warps(i).io.gmemAddr
      io.gmemWdata := warps(i).io.gmemWdata
    }
  }

  // Shared memory
  val shmAddr  = Wire(UInt(GpuParams.SharedAddrW.W))
  val shmEn    = Wire(Bool())
  val shmWe    = Wire(Bool())
  val shmWdata = Wire(Vec(warpWidth, SInt(dw.W)))
  shmAddr := 0.U; shmEn := false.B; shmWe := false.B
  shmWdata := VecInit.fill(warpWidth)(0.S(dw.W))

  for (i <- 0 until numWarps) {
    when(scheduler.io.grant(i)) {
      shmEn    := warps(i).io.shmEn
      shmWe    := warps(i).io.shmWe
      shmAddr  := warps(i).io.shmAddr
      shmWdata := warps(i).io.shmWdata
    }
  }

  shmRdata := sharedMem.read(shmAddr)
  when(shmEn && shmWe) { sharedMem.write(shmAddr, shmWdata) }
}
