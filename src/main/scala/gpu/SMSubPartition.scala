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

    val selectedWarp = Output(Valid(UInt(log2Ceil(numWarps).W)))
    val grant = Output(Vec(localWarps, Bool()))

    val coreValid = Input(Vec(warpWidth, Bool()))
    val coreOp = Input(Vec(warpWidth, UInt(4.W)))
    val coreRs1 = Input(Vec(warpWidth, SInt(dw.W)))
    val coreRs2 = Input(Vec(warpWidth, SInt(dw.W)))
    val coreRs3 = Input(Vec(warpWidth, SInt(dw.W)))
    val coreDone = Output(Vec(warpWidth, Bool()))
    val coreRd = Output(Vec(warpWidth, SInt(dw.W)))
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

  io.selectedWarp.valid := false.B
  io.selectedWarp.bits := 0.U
  for (w <- 0 until localWarps) {
    val globalWarpId = partitionId * localWarps + w
    when(!io.issueBlocked && scheduler.io.grant(w) && io.warpStarted(w)) {
      io.selectedWarp.valid := true.B
      io.selectedWarp.bits := globalWarpId.U
    }
  }

  val isExpInstrVec = Wire(Vec(warpWidth, Bool()))
  val isExpInstrRegVec = RegNext(isExpInstrVec)

  for (lane <- 0 until warpWidth) {
    val isExpInstr = io.coreOp(lane) === GpuOpcode.EXP
    isExpInstrVec(lane) := isExpInstr

    cudaCores(lane).io.valid := io.coreValid(lane) && !isExpInstr
    cudaCores(lane).io.op := io.coreOp(lane)
    cudaCores(lane).io.rs1 := io.coreRs1(lane)
    cudaCores(lane).io.rs2 := io.coreRs2(lane)
    cudaCores(lane).io.rs3 := io.coreRs3(lane)

    sfus(lane).io.valid := io.coreValid(lane) && isExpInstr
    sfus(lane).io.op := io.coreOp(lane)
    sfus(lane).io.x := io.coreRs1(lane)

    io.coreDone(lane) := Mux(isExpInstrRegVec(lane), sfus(lane).io.done, cudaCores(lane).io.done)
    io.coreRd(lane) := Mux(isExpInstrRegVec(lane), sfus(lane).io.result, cudaCores(lane).io.rd)
  }
}
