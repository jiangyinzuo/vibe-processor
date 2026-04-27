package ascend

import chisel3._

/** AI Core: integrates Scalar Unit, Cube Unit, Vector Unit. */
class AiCore(
    n:  Int = AscendParams.ArraySize,
    dw: Int = AscendParams.DataWidth,
    aw: Int = AscendParams.AccWidth
) extends Module {
  val io = IO(new Bundle {
    val start    = Input(Bool())
    val halted   = Output(Bool())
    val imemAddr = Output(UInt(8.W))
    val imemData = Input(UInt(AscendParams.InstrWidth.W))
    val ubEn     = Output(Bool())
    val ubWe     = Output(Bool())
    val ubAddr   = Output(UInt(AscendParams.UBAddrW.W))
    val ubWdata  = Output(Vec(n, SInt(aw.W)))
    val ubRdata  = Input(Vec(n, SInt(aw.W)))
  })

  val scalar = Module(new ScalarUnit(n, dw, aw))
  val cube   = Module(new CubeUnit(n, dw, aw))
  val vector = Module(new VectorUnit(n, aw))

  // Scalar <-> external
  scalar.io.start  := io.start
  io.halted        := scalar.io.halted
  io.imemAddr      := scalar.io.imemAddr
  scalar.io.imemData := io.imemData
  io.ubEn          := scalar.io.ubEn
  io.ubWe          := scalar.io.ubWe
  io.ubAddr        := scalar.io.ubAddr
  io.ubWdata       := scalar.io.ubWdata
  scalar.io.ubRdata := io.ubRdata

  // Scalar <-> Cube
  cube.io.start      := scalar.io.cubeStart
  scalar.io.cubeDone := cube.io.done
  cube.io.weightData := scalar.io.cubeWeight
  cube.io.actData    := scalar.io.cubeAct
  scalar.io.cubeResult := cube.io.result

  // Scalar <-> Vector
  vector.io.start    := scalar.io.vecStart
  scalar.io.vecDone  := vector.io.done
  vector.io.op       := scalar.io.vecOp
  vector.io.src1     := scalar.io.vecSrc1
  vector.io.src2     := scalar.io.vecSrc2
  scalar.io.vecDst   := vector.io.dst
}
