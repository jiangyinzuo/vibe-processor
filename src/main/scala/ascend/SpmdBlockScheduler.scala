package ascend

import chisel3._
import chisel3.util._

/** Reusable 1D SPMD logical-block scheduler.
  *
  * It tracks logical block issue/completion and assigns new blockIdx values to
  * idle physical execution slots. The module is intentionally independent of
  * AiCore internals so the AI CPU control plane can reuse it directly.
  */
class SpmdBlockScheduler(
    numSlots: Int,
    blockDimWidth: Int = AscendParams.BlockDimWidth
) extends Module {
  require(numSlots > 0, "SpmdBlockScheduler needs at least one physical slot")

  val io = IO(new Bundle {
    val start = Input(Bool())
    val blockDim = Input(UInt(blockDimWidth.W))
    val slotDone = Input(Vec(numSlots, Bool()))

    val launch = Output(Vec(numSlots, Valid(UInt(blockDimWidth.W))))
    val slotActive = Output(Vec(numSlots, Bool()))
    val slotBlockIdx = Output(Vec(numSlots, UInt(blockDimWidth.W)))
    val halted = Output(Bool())

    val dbgRunning = Output(Bool())
    val dbgNextBlock = Output(UInt(blockDimWidth.W))
    val dbgCompletedBlocks = Output(UInt(blockDimWidth.W))
  })

  val blockDimOrDefault = Mux(io.blockDim === 0.U, numSlots.U(blockDimWidth.W), io.blockDim)
  val activeBlockDim = RegInit(0.U(blockDimWidth.W))
  val nextBlock = RegInit(0.U(blockDimWidth.W))
  val completedBlocks = RegInit(0.U(blockDimWidth.W))
  val running = RegInit(false.B)
  val slotActive = RegInit(VecInit.fill(numSlots)(false.B))
  val slotBlockIdx = RegInit(VecInit.fill(numSlots)(0.U(blockDimWidth.W)))

  val done = Wire(Vec(numSlots, Bool()))
  for (i <- 0 until numSlots) {
    done(i) := slotActive(i) && io.slotDone(i)
  }

  val doneCount = PopCount(done)
  val scheduleBlockDim = Mux(io.start, blockDimOrDefault, activeBlockDim)
  val completedBase = Mux(io.start, 0.U, completedBlocks + doneCount)
  val nextBase = Mux(io.start, 0.U, nextBlock)
  val canSchedule = io.start || running

  var launchCount = 0.U(log2Ceil(numSlots + 1).W)
  for (i <- 0 until numSlots) {
    val launchId = nextBase + launchCount
    val launchValid = canSchedule && !slotActive(i) && launchId < scheduleBlockDim
    io.launch(i).valid := launchValid
    io.launch(i).bits := launchId
    launchCount = launchCount + launchValid.asUInt
  }

  val nextAfterLaunch = nextBase + launchCount
  val allIssued = nextAfterLaunch === scheduleBlockDim
  val allCompleted = completedBase === scheduleBlockDim

  when(io.start || running) {
    activeBlockDim := scheduleBlockDim
    nextBlock := nextAfterLaunch
    completedBlocks := completedBase
    running := !(allIssued && allCompleted)
  }

  when(io.start) {
    for (i <- 0 until numSlots) {
      slotActive(i) := false.B
      slotBlockIdx(i) := 0.U
    }
  }

  for (i <- 0 until numSlots) {
    when(done(i)) {
      slotActive(i) := false.B
    }
    when(io.launch(i).valid) {
      slotActive(i) := true.B
      slotBlockIdx(i) := io.launch(i).bits
    }
  }

  io.slotActive := slotActive
  io.slotBlockIdx := slotBlockIdx
  io.halted := !running && !io.start
  io.dbgRunning := running
  io.dbgNextBlock := nextBlock
  io.dbgCompletedBlocks := completedBlocks
}
