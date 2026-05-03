package gpu

import chisel3._
import chisel3.util._

/** CTA / thread-block scheduler.
  *
  * Real NVIDIA GPUs assign CTAs to SMs subject to resident block, warp, register, and shared-memory
  * limits. This toy scheduler models the resident CTA layer explicitly: each SM exposes a small
  * number of CTA slots, and the scheduler greedily fills free slots with CTA IDs from the current
  * grid.
  */
class CTAScheduler(
    numSMs: Int = GpuParams.NumSMs,
    maxCTAsPerSM: Int = GpuParams.MaxCTAsPerSM,
    numCTAs: Int = GpuParams.NumCTAs
) extends Module {
  require(numSMs > 0, "numSMs must be positive")
  require(maxCTAsPerSM > 1, "CTA layer should model multiple resident CTAs per SM")
  require(numCTAs > 0, "numCTAs must be positive")
  require(numCTAs <= (1 << GpuParams.CTAIdWidth), "numCTAs exceeds CTAIdWidth")

  private val totalSlots = numSMs * maxCTAsPerSM
  private val countW = math.max(GpuParams.CTAIdWidth, log2Ceil(numCTAs + totalSlots + 1))

  val io = IO(new Bundle {
    val start = Input(Bool())

    val slotActive = Input(Vec(numSMs, Vec(maxCTAsPerSM, Bool())))
    val ctaDone = Input(Vec(numSMs, Vec(maxCTAsPerSM, Valid(UInt(GpuParams.CTAIdWidth.W)))))

    val ctaLaunch = Output(Vec(numSMs, Vec(maxCTAsPerSM, Valid(UInt(GpuParams.CTAIdWidth.W)))))
    val running = Output(Bool())
    val allDone = Output(Bool())
  })

  val runningReg = RegInit(false.B)
  val nextCTA = RegInit(0.U(countW.W))
  val completedCTAs = RegInit(0.U(countW.W))

  val doneCount = PopCount((0 until numSMs).flatMap { sm =>
    (0 until maxCTAsPerSM).map(slot => io.ctaDone(sm)(slot).valid)
  })
  val completedBase = Mux(io.start, 0.U, completedCTAs + doneCount)
  val nextBase = Mux(io.start, 0.U, nextCTA)
  val canSchedule = io.start || runningReg

  var launchCount = 0.U(log2Ceil(totalSlots + 1).W)
  for (sm <- 0 until numSMs) {
    for (slot <- 0 until maxCTAsPerSM) {
      val launchId = nextBase + launchCount
      val launchValid = canSchedule && !io.slotActive(sm)(slot) && launchId < numCTAs.U
      io.ctaLaunch(sm)(slot).valid := launchValid
      io.ctaLaunch(sm)(slot).bits := launchId(GpuParams.CTAIdWidth - 1, 0)
      launchCount = launchCount + launchValid.asUInt
    }
  }

  val nextAfterLaunch = nextBase + launchCount
  val allIssued = nextAfterLaunch === numCTAs.U
  val allCompleted = completedBase === numCTAs.U

  when(io.start || runningReg) {
    nextCTA := nextAfterLaunch
    completedCTAs := completedBase
    runningReg := !(allIssued && allCompleted)
  }

  io.running := runningReg
  io.allDone := !runningReg && !io.start
}
