package ascend

import chisel3._
import chisel3.util._

/** Vector Core.
  *
  * Vector operations are now explicitly separated from the CubeCore path and operate through UB,
  * matching the Ascend programming model more closely.
  */
class VectorCore(
    n: Int = AscendParams.ArraySize,
    aw: Int = AscendParams.AccWidth
) extends Module {
  val io = IO(new Bundle {
    val start = Input(Bool())
    val op = Input(UInt(2.W))
    val src1Addr = Input(UInt(AscendParams.UBAddrW.W))
    val src2Addr = Input(UInt(AscendParams.UBAddrW.W))
    val done = Output(Bool())

    val ubEn = Output(Bool())
    val ubWe = Output(Bool())
    val ubAddr = Output(UInt(AscendParams.UBAddrW.W))
    val ubWdata = Output(Vec(n, SInt(aw.W)))
    val ubRdata = Input(Vec(n, SInt(aw.W)))
  })

  val vector = Module(new VectorUnit(n, aw))

  val sIdle :: sReadSrc1 :: sWaitSrc1 :: sReadSrc2 :: sWaitSrc2 :: sStartVec :: sWaitVec :: sWrite :: sDone :: Nil =
    Enum(9)
  val state = RegInit(sIdle)

  val opReg = RegInit(0.U(2.W))
  val src1AddrReg = RegInit(0.U(AscendParams.UBAddrW.W))
  val src2AddrReg = RegInit(0.U(AscendParams.UBAddrW.W))
  val src1Reg = RegInit(VecInit.fill(n)(0.S(aw.W)))
  val src2Reg = RegInit(VecInit.fill(n)(0.S(aw.W)))

  io.ubEn := false.B
  io.ubWe := false.B
  io.ubAddr := 0.U
  io.ubWdata := VecInit.fill(n)(0.S(aw.W))
  io.done := state === sDone

  vector.io.start := state === sStartVec
  vector.io.op := opReg
  vector.io.src1 := src1Reg
  vector.io.src2 := src2Reg

  switch(state) {
    is(sIdle) {
      when(io.start) {
        opReg := io.op
        src1AddrReg := io.src1Addr
        src2AddrReg := io.src2Addr
        state := sReadSrc1
      }
    }
    is(sReadSrc1) {
      io.ubEn := true.B
      io.ubAddr := src1AddrReg
      state := sWaitSrc1
    }
    is(sWaitSrc1) {
      io.ubEn := true.B
      io.ubAddr := src1AddrReg
      src1Reg := io.ubRdata
      state := sReadSrc2
    }
    is(sReadSrc2) {
      io.ubEn := true.B
      io.ubAddr := src2AddrReg
      state := sWaitSrc2
    }
    is(sWaitSrc2) {
      io.ubEn := true.B
      io.ubAddr := src2AddrReg
      src2Reg := io.ubRdata
      state := sStartVec
    }
    is(sStartVec) {
      state := sWaitVec
    }
    is(sWaitVec) {
      when(vector.io.done) {
        state := sWrite
      }
    }
    is(sWrite) {
      io.ubEn := true.B
      io.ubWe := true.B
      io.ubAddr := src1AddrReg
      io.ubWdata := vector.io.dst
      state := sDone
    }
    is(sDone) {
      state := sIdle
    }
  }
}
