package ascend

import chisel3._
import chisel3.util._

/** Toy device control CPU.
  *
  * This models the runtime/task-scheduler side of the device rather than the AI CPU execution
  * engine. It owns SPMD logical-block dispatch and launches physical AiCores as slots become idle.
  */
class ControlCpu(
    numCores: Int = AscendParams.NumCores,
    blockDimWidth: Int = AscendParams.BlockDimWidth
) extends Module {
  require(numCores > 0, "ControlCpu needs at least one AiCore")

  val io = IO(new Bundle {
    val start = Input(Bool())
    val blockDim = Input(UInt(blockDimWidth.W))
    val coreHalted = Input(Vec(numCores, Bool()))

    val coreLaunch = Output(Vec(numCores, Valid(UInt(blockDimWidth.W))))
    val coreActive = Output(Vec(numCores, Bool()))
    val coreBlockIdx = Output(Vec(numCores, UInt(blockDimWidth.W)))
    val halted = Output(Bool())

    val dbgRunning = Output(Bool())
    val dbgNextBlock = Output(UInt(blockDimWidth.W))
    val dbgCompletedBlocks = Output(UInt(blockDimWidth.W))
  })

  val scheduler = Module(new SpmdBlockScheduler(numCores, blockDimWidth))
  scheduler.io.start := io.start
  scheduler.io.blockDim := io.blockDim
  scheduler.io.slotDone := io.coreHalted

  io.coreLaunch := scheduler.io.launch
  io.coreActive := scheduler.io.slotActive
  io.coreBlockIdx := scheduler.io.slotBlockIdx
  io.halted := scheduler.io.halted
  io.dbgRunning := scheduler.io.dbgRunning
  io.dbgNextBlock := scheduler.io.dbgNextBlock
  io.dbgCompletedBlocks := scheduler.io.dbgCompletedBlocks
}
