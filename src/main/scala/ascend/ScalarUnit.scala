package ascend

import chisel3._
import chisel3.util._

/** Instruction opcodes. */
object Opcode {
  val NOP    = 0x0.U(4.W)
  val HALT   = 0x1.U(4.W)
  val LOAD   = 0x2.U(4.W)
  val STORE  = 0x3.U(4.W)
  val MATMUL = 0x4.U(4.W)
  val VECADD = 0x5.U(4.W)
  val RELU   = 0x6.U(4.W)
}

/** Buffer select for LOAD/STORE. */
object BufSel {
  val L0_A = 0.U(2.W) // weight
  val L0_B = 1.U(2.W) // activation
  val L0_C = 2.U(2.W) // result
  val VEC  = 3.U(2.W)
}

/** Scalar Unit: instruction fetch, decode, and execution control FSM. */
class ScalarUnit(
    n:  Int = AscendParams.ArraySize,
    dw: Int = AscendParams.DataWidth,
    aw: Int = AscendParams.AccWidth
) extends Module {
  val io = IO(new Bundle {
    val start  = Input(Bool())
    val halted = Output(Bool())
    // Instruction memory
    val imemAddr = Output(UInt(8.W))
    val imemData = Input(UInt(AscendParams.InstrWidth.W))
    // Unified Buffer
    val ubEn    = Output(Bool())
    val ubWe    = Output(Bool())
    val ubAddr  = Output(UInt(AscendParams.UBAddrW.W))
    val ubWdata = Output(Vec(n, SInt(aw.W)))
    val ubRdata = Input(Vec(n, SInt(aw.W)))
    // Cube Unit
    val cubeStart  = Output(Bool())
    val cubeDone   = Input(Bool())
    val cubeWeight = Output(Vec(n, Vec(n, SInt(dw.W))))
    val cubeAct    = Output(Vec(n, Vec(n, SInt(dw.W))))
    val cubeResult = Input(Vec(n, Vec(n, SInt(aw.W))))
    // Vector Unit
    val vecStart = Output(Bool())
    val vecDone  = Input(Bool())
    val vecOp    = Output(UInt(2.W))
    val vecSrc1  = Output(Vec(n, SInt(aw.W)))
    val vecSrc2  = Output(Vec(n, SInt(aw.W)))
    val vecDst   = Input(Vec(n, SInt(aw.W)))
  })

  // FSM states
  // scalafmt: { maxColumn = 120 }
  val sIdle :: sFetch :: sDecode :: sLoad0 :: sLoad1 :: sLoad2 :: sStore0 :: sStore1 :: sMatmul :: sVec :: sHalted :: Nil = Enum(11)

  val state = RegInit(sIdle)
  val pc    = RegInit(0.U(8.W))

  // Latched instruction fields
  val opLat     = RegInit(0.U(4.W))
  val dstSelLat = RegInit(0.U(2.W))
  val memAddrLat = RegInit(0.U(16.W))
  val vecSrc1Lat = RegInit(0.U(6.W))
  val vecSrc2Lat = RegInit(0.U(6.W))
  val rowCnt    = RegInit(0.U(4.W))

  val rowIdx = rowCnt(1, 0) // truncate to 2 bits for Vec(4)
  val weightBuf = RegInit(VecInit.fill(n, n)(0.S(dw.W)))
  val actBuf    = RegInit(VecInit.fill(n, n)(0.S(dw.W)))

  // Decode fields from instruction word
  val instr   = io.imemData
  val op      = instr(31, 28)
  val dstSel  = instr(27, 26)
  val memAddr = instr(19, 4)
  val vecSrc1Addr = instr(27, 22)
  val vecSrc2Addr = instr(21, 16)

  io.imemAddr := pc
  io.halted   := state === sHalted

  // Default outputs
  val ubEn    = WireDefault(false.B)
  val ubWe    = WireDefault(false.B)
  val ubAddr  = WireDefault(0.U(AscendParams.UBAddrW.W))
  val ubWdata = WireDefault(VecInit.fill(n)(0.S(aw.W)))

  io.ubEn    := ubEn
  io.ubWe    := ubWe
  io.ubAddr  := ubAddr
  io.ubWdata := ubWdata

  io.cubeStart  := false.B
  io.cubeWeight := weightBuf
  io.cubeAct    := actBuf

  io.vecStart := false.B
  io.vecOp    := 0.U
  io.vecSrc1  := VecInit.fill(n)(0.S(aw.W))
  io.vecSrc2  := VecInit.fill(n)(0.S(aw.W))

  switch(state) {
    is(sIdle) {
      when(io.start) {
        pc    := 0.U
        state := sFetch
      }
    }

    is(sFetch) {
      // imemAddr = pc, data available combinationally
      state := sDecode
    }

    is(sDecode) {
      opLat      := op
      dstSelLat  := dstSel
      memAddrLat := memAddr
      vecSrc1Lat := vecSrc1Addr
      vecSrc2Lat := vecSrc2Addr

      switch(op) {
        is(Opcode.NOP) {
          pc    := pc + 1.U
          state := sFetch
        }
        is(Opcode.HALT) {
          state := sHalted
        }
        is(Opcode.LOAD) {
          rowCnt := 0.U
          state  := sLoad0
        }
        is(Opcode.STORE) {
          rowCnt := 0.U
          state  := sStore0
        }
        is(Opcode.MATMUL) {
          state := sMatmul
        }
        is(Opcode.VECADD, Opcode.RELU) {
          state := sVec
        }
      }
    }

    // LOAD: read N rows from UB into weight/act buffer
    is(sLoad0) {
      ubEn   := true.B
      ubAddr := memAddrLat + rowCnt
      state  := sLoad1
    }
    is(sLoad1) {
      // Keep addr stable for SyncReadMem read latency
      ubAddr := memAddrLat + rowCnt
      state := sLoad2
    }
    is(sLoad2) {
      // Store UB data into target buffer
      when(dstSelLat === BufSel.L0_A) {
        for (j <- 0 until n) {
          weightBuf(rowIdx)(j) := io.ubRdata(j)(dw - 1, 0).asSInt
        }
      }.elsewhen(dstSelLat === BufSel.L0_B) {
        for (j <- 0 until n) {
          actBuf(rowIdx)(j) := io.ubRdata(j)(dw - 1, 0).asSInt
        }
      }

      when(rowCnt === (n - 1).U) {
        pc    := pc + 1.U
        state := sFetch
      }.otherwise {
        rowCnt := rowCnt + 1.U
        state  := sLoad0
      }
    }

    // STORE: write N rows from cube result to UB
    is(sStore0) {
      ubEn   := true.B
      ubWe   := true.B
      ubAddr := memAddrLat + rowCnt
      for (j <- 0 until n) {
        ubWdata(j) := io.cubeResult(rowIdx)(j)
      }
      state := sStore1
    }
    is(sStore1) {
      when(rowCnt === (n - 1).U) {
        pc    := pc + 1.U
        state := sFetch
      }.otherwise {
        rowCnt := rowCnt + 1.U
        state  := sStore0
      }
    }

    // MATMUL
    is(sMatmul) {
      io.cubeStart := true.B
      when(io.cubeDone) {
        io.cubeStart := false.B
        pc    := pc + 1.U
        state := sFetch
      }
    }

    // VECADD / RELU
    is(sVec) {
      io.vecOp := Mux(opLat === Opcode.RELU, 1.U, 0.U)
      for (j <- 0 until n) {
        io.vecSrc1(j) := io.cubeResult(vecSrc1Lat(1, 0))(j)
        io.vecSrc2(j) := io.cubeResult(vecSrc2Lat(1, 0))(j)
      }
      io.vecStart := true.B
      when(io.vecDone) {
        io.vecStart := false.B
        pc    := pc + 1.U
        state := sFetch
      }
    }

    is(sHalted) {
      // Stay halted
    }
  }
}
