package gpu

import chisel3._
import chisel3.util._

/** SM sub-partition.
  *
  * This models the per-partition part of an NVIDIA-like SM:
  *   - one local warp scheduler
  *   - one dispatch lane group
  *   - one CUDA Core and one SFU per lane in the group
  *
  * The parent SM still owns resources that are shared across sub-partitions,
  * such as the register file, shared memory, and global-memory request path.
  */
class SMSubPartition(
    partitionId: Int,
    numWarps: Int,
    localWarps: Int,
    warpWidth: Int,
    dw: Int = GpuParams.DataWidth
) extends Module {
  val io = IO(new Bundle {
    val issueBlocked = Input(Bool())

    val warpState = Input(Vec(localWarps, WarpState()))
    val warpStarted = Input(Vec(localWarps, Bool()))

    val selectedWarp = Output(Vec(localWarps, Valid(UInt(log2Ceil(numWarps).W))))
    val grant = Output(Vec(localWarps, Bool()))

    val coreValid = Input(Vec(warpWidth, Bool()))
    val coreOp = Input(Vec(warpWidth, UInt(4.W)))
    val coreRs1 = Input(Vec(warpWidth, SInt(dw.W)))
    val coreRs2 = Input(Vec(warpWidth, SInt(dw.W)))
    val coreRs3 = Input(Vec(warpWidth, SInt(dw.W)))
    val coreDone = Output(Vec(warpWidth, Bool()))
    val coreRd = Output(Vec(warpWidth, SInt(dw.W)))

    val sfuValid = Input(Vec(warpWidth, Bool()))
    val sfuOp = Input(Vec(warpWidth, UInt(4.W)))
    val sfuRs1 = Input(Vec(warpWidth, SInt(dw.W)))
    val sfuDone = Output(Vec(warpWidth, Bool()))
    val sfuRd = Output(Vec(warpWidth, SInt(dw.W)))
  })

  val scheduler = Module(new WarpScheduler(localWarps))
  val cudaCores = Array.fill(warpWidth)(Module(new CudaCore(dw)))
  val sfus = Array.fill(warpWidth)(Module(new SFU(dw)))

  for (w <- 0 until localWarps) {
    scheduler.io.warpHalted(w) :=
      (io.warpState(w) === WarpState.Halted) ||
        (io.warpState(w) === WarpState.Stalled)
  }
  io.grant := scheduler.io.grant

  val localWarpW = math.max(1, log2Ceil(localWarps))
  val ready = VecInit((0 until localWarps).map { w =>
    io.warpStarted(w) &&
      io.warpState(w) =/= WarpState.Halted &&
      io.warpState(w) =/= WarpState.Stalled
  })
  val grantIdx = OHToUInt(scheduler.io.grant)

  for (c <- 0 until localWarps) {
    val localIdx = ((grantIdx + c.U) % localWarps.U)(localWarpW - 1, 0)
    io.selectedWarp(c).valid := !io.issueBlocked && ready(localIdx)
    io.selectedWarp(c).bits := (partitionId * localWarps).U(log2Ceil(numWarps).W) + localIdx
  }

  for (lane <- 0 until warpWidth) {
    cudaCores(lane).io.valid := io.coreValid(lane)
    cudaCores(lane).io.op := io.coreOp(lane)
    cudaCores(lane).io.rs1 := io.coreRs1(lane)
    cudaCores(lane).io.rs2 := io.coreRs2(lane)
    cudaCores(lane).io.rs3 := io.coreRs3(lane)

    sfus(lane).io.valid := io.sfuValid(lane)
    sfus(lane).io.op := io.sfuOp(lane)
    sfus(lane).io.x := io.sfuRs1(lane)

    io.coreDone(lane) := cudaCores(lane).io.done
    io.coreRd(lane) := cudaCores(lane).io.rd
    io.sfuDone(lane) := sfus(lane).io.done
    io.sfuRd(lane) := sfus(lane).io.result
  }
}
