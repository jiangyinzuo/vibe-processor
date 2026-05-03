package ascend

import chisel3._
import chisel3.util._

object CubeLocalTarget {
  val ACT    = 0.U(1.W)
  val WEIGHT = 1.U(1.W)
}

/** Cube Core.
  *
  * This is the explicit Cube side of the toy Ascend core. It owns the
  * Cube-facing local memories: L1 staging writes land in L0A/L0B, Cube writes
  * results into L0C, and MTE3 reads L0C back out.
  */
class CubeCore(
    n:  Int = AscendParams.ArraySize,
    dw: Int = AscendParams.DataWidth,
    aw: Int = AscendParams.AccWidth,
    tileSlots: Int = AscendParams.CubeTileSlots
) extends Module {
  require(tileSlots >= 2, "CubeCore needs at least two tile slots for LOAD/MATMUL decoupling")

  val io = IO(new Bundle {
    val start       = Input(Bool())
    val done        = Output(Bool())
    val resultValid = Output(Bool())

    val mte1Write = Flipped(Valid(new Bundle {
      val target = UInt(1.W)
      val row    = UInt(log2Ceil(n).W)
      val data   = Vec(n, SInt(dw.W))
    }))

    val l0cReadRow = Input(UInt(log2Ceil(n).W))
    val l0cReadData = Output(Vec(n, SInt(aw.W)))

    val dbgFeeding = Output(Bool())
  })

  val cube = Module(new CubeUnit(n, dw, aw))

  val l0a = RegInit(VecInit.fill(tileSlots, n, n)(0.S(dw.W)))
  val l0b = RegInit(VecInit.fill(tileSlots, n, n)(0.S(dw.W)))
  val l0c     = RegInit(VecInit.fill(n, n)(0.S(aw.W)))

  val slotW = log2Ceil(tileSlots)
  val fillSlot = RegInit(0.U(slotW.W))
  val computeSlot = RegInit(0.U(slotW.W))
  val activeSlot = RegInit(0.U(slotW.W))
  val issueSlot = RegInit(0.U(slotW.W))
  val l0aReady = RegInit(VecInit.fill(tileSlots)(false.B))
  val l0bReady = RegInit(VecInit.fill(tileSlots)(false.B))

  val sIdle :: sLaunch :: sRun :: sDone :: Nil = Enum(4)
  val state = RegInit(sIdle)

  val writeLastRow = io.mte1Write.valid && io.mte1Write.bits.row === (n - 1).U
  val writeActDone = writeLastRow && io.mte1Write.bits.target === CubeLocalTarget.ACT
  val writeWeightDone = writeLastRow && io.mte1Write.bits.target === CubeLocalTarget.WEIGHT
  val slotReadyAfterWrite =
    (l0aReady(fillSlot) || writeActDone) && (l0bReady(fillSlot) || writeWeightDone)

  when(io.mte1Write.valid) {
    when(io.mte1Write.bits.target === CubeLocalTarget.ACT) {
      l0a(fillSlot)(io.mte1Write.bits.row) := io.mte1Write.bits.data
      when(writeLastRow) { l0aReady(fillSlot) := true.B }
    }.otherwise {
      l0b(fillSlot)(io.mte1Write.bits.row) := io.mte1Write.bits.data
      when(writeLastRow) { l0bReady(fillSlot) := true.B }
    }

    when(writeLastRow && slotReadyAfterWrite) {
      fillSlot := fillSlot + 1.U
    }
  }

  // Split tile issue and CubeUnit launch so ready-bit selection is not on the
  // same cycle as the high-fanout Cube start/clear path.
  val cubeReadSlot = Mux(state === sLaunch, issueSlot, activeSlot)
  val actData = Wire(Vec(n, Vec(n, SInt(dw.W))))
  val weightData = Wire(Vec(n, Vec(n, SInt(dw.W))))
  actData := l0a(cubeReadSlot)
  weightData := l0b(cubeReadSlot)

  val startCube = WireDefault(false.B)

  switch(state) {
    is(sIdle) {
      when(io.start && l0aReady(computeSlot) && l0bReady(computeSlot)) {
        issueSlot := computeSlot
        state := sLaunch
      }
    }
    is(sLaunch) {
      activeSlot := issueSlot
      l0aReady(issueSlot) := false.B
      l0bReady(issueSlot) := false.B
      computeSlot := issueSlot + 1.U
      startCube := true.B
      state := sRun
    }
    is(sRun) {
      when(cube.io.done) {
        l0c := cube.io.result
        state := sDone
      }
    }
    is(sDone) {
      state := sIdle
    }
  }

  cube.io.start := startCube
  cube.io.weightData := weightData
  cube.io.actData := actData

  io.done := state === sDone
  io.resultValid := state === sDone
  io.l0cReadData := l0c(io.l0cReadRow)
  io.dbgFeeding := cube.io.dbgFeeding
}
