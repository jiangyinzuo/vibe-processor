package gpu

import chisel3._
import chisel3.util._

/** Round-Robin Warp Scheduler: selects one active (non-halted) warp per cycle. */
class WarpScheduler(numWarps: Int = GpuParams.NumWarps) extends Module {
  val io = IO(new Bundle {
    val warpHalted = Input(Vec(numWarps, Bool()))
    val grant      = Output(Vec(numWarps, Bool()))
    val allHalted  = Output(Bool())
  })

  val ptr = RegInit(0.U(log2Ceil(numWarps).W))

  // Build rotated active mask: rotate by ptr, then priority-encode
  val active = VecInit((0 until numWarps).map(i => !io.warpHalted(i)))
  io.allHalted := !active.asUInt.orR

  // Rotate active mask so ptr is at position 0
  val rotated = VecInit((0 until numWarps).map(i =>
    active(((i.U +& ptr) % numWarps.U)(log2Ceil(numWarps) - 1, 0))
  ))

  // Priority encoder on rotated mask (find first active)
  val sel = PriorityEncoder(rotated.asUInt)

  // Map back to original index
  val grantIdx = ((sel +& ptr) % numWarps.U)(log2Ceil(numWarps) - 1, 0)

  // Output
  io.grant := VecInit.fill(numWarps)(false.B)
  when(!io.allHalted) {
    io.grant(grantIdx) := true.B
    ptr := ((grantIdx +& 1.U) % numWarps.U)(log2Ceil(numWarps) - 1, 0)
  }
}
