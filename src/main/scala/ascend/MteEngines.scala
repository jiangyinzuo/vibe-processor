package ascend

import chisel3._
import chisel3.util._

/** MTE1: UB -> L1 staging -> L0A/L0B. */
class Mte1(
    n: Int = AscendParams.ArraySize,
    dw: Int = AscendParams.DataWidth,
    aw: Int = AscendParams.AccWidth
) extends Module {
  val io = IO(new Bundle {
    val start = Input(Bool())
    val dstSel = Input(UInt(2.W))
    val ubBase = Input(UInt(AscendParams.UBAddrW.W))
    val busy = Output(Bool())
    val done = Output(Bool())

    val ubEn = Output(Bool())
    val ubAddr = Output(UInt(AscendParams.UBAddrW.W))
    val ubRdata = Input(Vec(n, SInt(aw.W)))

    val cubeWrite = Valid(new Bundle {
      val target = UInt(1.W)
      val row = UInt(log2Ceil(n).W)
      val data = Vec(n, SInt(dw.W))
    })
  })

  val sIdle :: sRead :: sWait :: sWrite :: sDone :: Nil = Enum(5)
  val state = RegInit(sIdle)
  val rowCnt = RegInit(0.U(log2Ceil(n + 1).W))
  val ubBaseReg = RegInit(0.U(AscendParams.UBAddrW.W))
  val dstSelReg = RegInit(0.U(2.W))
  val l1Row = RegInit(VecInit.fill(n)(0.S(dw.W)))

  io.busy := state =/= sIdle
  io.done := state === sDone
  io.ubEn := false.B
  io.ubAddr := 0.U
  io.cubeWrite.valid := false.B
  io.cubeWrite.bits.target := CubeLocalTarget.ACT
  io.cubeWrite.bits.row := rowCnt(log2Ceil(n) - 1, 0)
  io.cubeWrite.bits.data := l1Row

  switch(state) {
    is(sIdle) {
      when(io.start) {
        ubBaseReg := io.ubBase
        dstSelReg := io.dstSel
        rowCnt := 0.U
        state := sRead
      }
    }
    is(sRead) {
      io.ubEn := true.B
      io.ubAddr := ubBaseReg + rowCnt
      state := sWait
    }
    is(sWait) {
      io.ubEn := true.B
      io.ubAddr := ubBaseReg + rowCnt
      for (j <- 0 until n) {
        l1Row(j) := io.ubRdata(j)(dw - 1, 0).asSInt
      }
      state := sWrite
    }
    is(sWrite) {
      io.cubeWrite.valid := true.B
      io.cubeWrite.bits.target := Mux(
        dstSelReg === BufSel.L0_B,
        CubeLocalTarget.ACT,
        CubeLocalTarget.WEIGHT
      )
      io.cubeWrite.bits.row := rowCnt(log2Ceil(n) - 1, 0)
      io.cubeWrite.bits.data := l1Row
      rowCnt := rowCnt + 1.U
      state := Mux(rowCnt === (n - 1).U, sDone, sRead)
    }
    is(sDone) {
      state := sIdle
    }
  }
}

/** MTE2: L2/GM-facing path <-> UB. */
class Mte2(
    n: Int = AscendParams.ArraySize,
    aw: Int = AscendParams.AccWidth
) extends Module {
  val io = IO(new Bundle {
    val start = Input(Bool())
    val isStore = Input(Bool())
    val l2Base = Input(UInt(AscendParams.L2AddrW.W))
    val ubBase = Input(UInt(AscendParams.UBAddrW.W))
    val busy = Output(Bool())
    val done = Output(Bool())

    val ubEn = Output(Bool())
    val ubWe = Output(Bool())
    val ubAddr = Output(UInt(AscendParams.UBAddrW.W))
    val ubWdata = Output(Vec(n, SInt(aw.W)))
    val ubRdata = Input(Vec(n, SInt(aw.W)))

    val l2En = Output(Bool())
    val l2We = Output(Bool())
    val l2Addr = Output(UInt(AscendParams.L2AddrW.W))
    val l2Wdata = Output(Vec(n, SInt(aw.W)))
    val l2Rdata = Input(Vec(n, SInt(aw.W)))
  })

  val sIdle :: sLoadRd :: sLoadWait :: sLoadWb :: sStoreRd :: sStoreWait :: sStoreWr :: sDone :: Nil =
    Enum(8)
  val state = RegInit(sIdle)
  val rowCnt = RegInit(0.U(log2Ceil(n + 1).W))
  val l2BaseReg = RegInit(0.U(AscendParams.L2AddrW.W))
  val ubBaseReg = RegInit(0.U(AscendParams.UBAddrW.W))
  val isStoreReg = RegInit(false.B)
  val l2DataLat = RegInit(VecInit.fill(n)(0.S(aw.W)))

  io.busy := state =/= sIdle && state =/= sDone
  io.done := state === sDone
  io.ubEn := false.B
  io.ubWe := false.B
  io.ubAddr := 0.U
  io.ubWdata := VecInit.fill(n)(0.S(aw.W))
  io.l2En := false.B
  io.l2We := false.B
  io.l2Addr := 0.U
  io.l2Wdata := VecInit.fill(n)(0.S(aw.W))

  switch(state) {
    is(sIdle) {
      when(io.start) {
        l2BaseReg := io.l2Base
        ubBaseReg := io.ubBase
        isStoreReg := io.isStore
        rowCnt := 0.U
        state := Mux(io.isStore, sStoreRd, sLoadRd)
      }
    }
    is(sLoadRd) {
      io.l2En := true.B
      io.l2Addr := l2BaseReg + rowCnt
      state := sLoadWait
    }
    is(sLoadWait) {
      io.l2En := true.B
      io.l2Addr := l2BaseReg + rowCnt
      l2DataLat := io.l2Rdata
      state := sLoadWb
    }
    is(sLoadWb) {
      io.ubEn := true.B
      io.ubWe := true.B
      io.ubAddr := ubBaseReg + rowCnt
      io.ubWdata := l2DataLat
      rowCnt := rowCnt + 1.U
      state := Mux(rowCnt === (n - 1).U, sDone, sLoadRd)
    }
    is(sStoreRd) {
      io.ubEn := true.B
      io.ubAddr := ubBaseReg + rowCnt
      state := sStoreWait
    }
    is(sStoreWait) {
      io.ubEn := true.B
      io.ubAddr := ubBaseReg + rowCnt
      state := sStoreWr
    }
    is(sStoreWr) {
      io.ubEn := true.B
      io.ubAddr := ubBaseReg + rowCnt
      io.l2En := true.B
      io.l2We := true.B
      io.l2Addr := l2BaseReg + rowCnt
      io.l2Wdata := io.ubRdata
      rowCnt := rowCnt + 1.U
      state := Mux(rowCnt === (n - 1).U, sDone, sStoreRd)
    }
    is(sDone) {
      state := sIdle
    }
  }
}

/** MTE3: L0C -> UB. */
class Mte3(
    n: Int = AscendParams.ArraySize,
    aw: Int = AscendParams.AccWidth
) extends Module {
  val io = IO(new Bundle {
    val start = Input(Bool())
    val ubBase = Input(UInt(AscendParams.UBAddrW.W))
    val busy = Output(Bool())
    val done = Output(Bool())

    val l0cReadRow = Output(UInt(log2Ceil(n).W))
    val l0cReadData = Input(Vec(n, SInt(aw.W)))

    val ubEn = Output(Bool())
    val ubWe = Output(Bool())
    val ubAddr = Output(UInt(AscendParams.UBAddrW.W))
    val ubWdata = Output(Vec(n, SInt(aw.W)))
  })

  val sIdle :: sWrite :: sDone :: Nil = Enum(3)
  val state = RegInit(sIdle)
  val rowCnt = RegInit(0.U(log2Ceil(n + 1).W))
  val ubBaseReg = RegInit(0.U(AscendParams.UBAddrW.W))

  io.busy := state =/= sIdle && state =/= sDone
  io.done := state === sDone
  io.l0cReadRow := rowCnt(log2Ceil(n) - 1, 0)
  io.ubEn := false.B
  io.ubWe := false.B
  io.ubAddr := 0.U
  io.ubWdata := io.l0cReadData

  switch(state) {
    is(sIdle) {
      when(io.start) {
        ubBaseReg := io.ubBase
        rowCnt := 0.U
        state := sWrite
      }
    }
    is(sWrite) {
      io.ubEn := true.B
      io.ubWe := true.B
      io.ubAddr := ubBaseReg + rowCnt
      io.ubWdata := io.l0cReadData
      rowCnt := rowCnt + 1.U
      state := Mux(rowCnt === (n - 1).U, sDone, sWrite)
    }
    is(sDone) {
      state := sIdle
    }
  }
}
