package ascend

import chisel3._
import chisel3.util._

/** Instruction opcodes. */
object Opcode {
  val NOP       = 0x0.U(4.W)
  val HALT      = 0x1.U(4.W)
  val LOAD      = 0x2.U(4.W)
  val STORE     = 0x3.U(4.W)
  val MATMUL    = 0x4.U(4.W)
  val VECADD    = 0x5.U(4.W)
  val RELU      = 0x6.U(4.W)
  val DMA_LOAD  = 0x8.U(4.W)
  val DMA_STORE = 0x9.U(4.W)
  val DMA_WAIT  = 0xA.U(4.W)
}

/** Buffer select for LOAD/STORE. Existing encodings are preserved. */
object BufSel {
  val L0_A = 0.U(2.W)
  val L0_B = 1.U(2.W)
  val L0_C = 2.U(2.W)
  val VEC  = 3.U(2.W)
}

/** Scalar Unit: instruction fetch, decode, and command dispatch.
  *
  * The scalar pipeline no longer owns Cube/Vector local data. It issues
  * commands to the decoupled CubeCore, VectorCore, and MTE engines and waits on their
  * completion when the ISA requires blocking behavior.
  */
class ScalarUnit(
    n:  Int = AscendParams.ArraySize,
    dw: Int = AscendParams.DataWidth,
    aw: Int = AscendParams.AccWidth
) extends Module {
  val io = IO(new Bundle {
    val start  = Input(Bool())
    val halted = Output(Bool())

    val imemAddr = Output(UInt(8.W))
    val imemData = Input(UInt(AscendParams.InstrWidth.W))

    val mte1Start = Output(Bool())
    val mte1Busy  = Input(Bool())
    val mte1Done  = Input(Bool())
    val mte1DstSel = Output(UInt(2.W))
    val mte1UbAddr = Output(UInt(AscendParams.UBAddrW.W))

    val mte3Start = Output(Bool())
    val mte3Done  = Input(Bool())
    val mte3UbAddr = Output(UInt(AscendParams.UBAddrW.W))

    val cubeStart = Output(Bool())
    val cubeDone  = Input(Bool())

    val vectorStart = Output(Bool())
    val vectorDone  = Input(Bool())
    val vectorOp    = Output(UInt(2.W))
    val vectorSrc1Addr = Output(UInt(AscendParams.UBAddrW.W))
    val vectorSrc2Addr = Output(UInt(AscendParams.UBAddrW.W))

    val dmaQueueEnq    = Output(Bool())
    val dmaQueueFull   = Input(Bool())
    val dmaQueueEmpty  = Input(Bool())
    val dmaEnqIsStore  = Output(Bool())
    val dmaEnqL2Addr   = Output(UInt(AscendParams.L2AddrW.W))
    val dmaEnqUbAddr   = Output(UInt(AscendParams.UBAddrW.W))

    val dbgState = Output(UInt(4.W))
    val dbgOpLat = Output(UInt(4.W))
  })

  // Keep enum ordering stable for existing performance-counter logic:
  // sDecode=2, sMatmul=8, sVec=9, sDmaWait=10.
  val sIdle :: sFetch :: sDecode :: sLoad0 :: sLoad1 :: sLoad2 :: sStore0 :: sStore1 :: sMatmul :: sVec :: sDmaWait :: sHalted :: Nil =
    Enum(12)

  val state = RegInit(sIdle)
  val pc    = RegInit(0.U(8.W))

  val opLat      = RegInit(0.U(4.W))
  val dstSelLat  = RegInit(0.U(2.W))
  val memAddrLat = RegInit(0.U(AscendParams.UBAddrW.W))
  val vecSrc1Lat = RegInit(0.U(AscendParams.UBAddrW.W))
  val vecSrc2Lat = RegInit(0.U(AscendParams.UBAddrW.W))

  val instr = io.imemData
  val op = instr(31, 28)
  val dstSel = instr(27, 26)
  val memAddr = instr(19, 4)
  val vecSrc1Addr = instr(27, 22)
  val vecSrc2Addr = instr(21, 16)
  val dmaUbBase = instr(27, 20)
  val dmaL2Base = instr(19, 4)

  io.imemAddr := pc
  io.halted := state === sHalted
  io.dbgState := state.asUInt
  io.dbgOpLat := opLat

  io.mte1Start := false.B
  io.mte1DstSel := dstSelLat
  io.mte1UbAddr := memAddrLat

  io.mte3Start := false.B
  io.mte3UbAddr := memAddrLat

  io.cubeStart := false.B

  io.vectorStart := false.B
  io.vectorOp := Mux(opLat === Opcode.RELU, 1.U, 0.U)
  io.vectorSrc1Addr := vecSrc1Lat
  io.vectorSrc2Addr := vecSrc2Lat

  io.dmaQueueEnq := false.B
  io.dmaEnqIsStore := false.B
  io.dmaEnqL2Addr := 0.U
  io.dmaEnqUbAddr := 0.U

  switch(state) {
    is(sIdle) {
      when(io.start) {
        pc := 0.U
        state := sFetch
      }
    }

    is(sFetch) {
      state := sDecode
    }

    is(sDecode) {
      opLat := op
      dstSelLat := dstSel
      memAddrLat := memAddr
      vecSrc1Lat := vecSrc1Addr
      vecSrc2Lat := vecSrc2Addr

      switch(op) {
        is(Opcode.NOP) {
          pc := pc + 1.U
          state := sFetch
        }
        is(Opcode.HALT) {
          state := sHalted
        }
        is(Opcode.LOAD) {
          state := sLoad0
        }
        is(Opcode.STORE) {
          state := sStore0
        }
        is(Opcode.MATMUL) {
          state := sMatmul
        }
        is(Opcode.VECADD, Opcode.RELU) {
          state := sVec
        }
        is(Opcode.DMA_LOAD, Opcode.DMA_STORE) {
          when(!io.dmaQueueFull) {
            io.dmaQueueEnq := true.B
            io.dmaEnqIsStore := op === Opcode.DMA_STORE
            io.dmaEnqL2Addr := dmaL2Base
            io.dmaEnqUbAddr := dmaUbBase
            pc := pc + 1.U
            state := sFetch
          }
        }
        is(Opcode.DMA_WAIT) {
          state := sDmaWait
        }
      }
    }

    is(sLoad0) {
      when(!io.mte1Busy) {
        io.mte1Start := true.B
        io.mte1DstSel := dstSelLat
        io.mte1UbAddr := memAddrLat
        pc := pc + 1.U
        state := sFetch
      }
    }
    is(sLoad1) {
      when(io.mte1Done) {
        pc := pc + 1.U
        state := sFetch
      }
    }
    is(sLoad2) {
      state := sFetch
    }

    is(sStore0) {
      io.mte3Start := true.B
      io.mte3UbAddr := memAddrLat
      state := sStore1
    }
    is(sStore1) {
      when(io.mte3Done) {
        pc := pc + 1.U
        state := sFetch
      }
    }

    is(sMatmul) {
      io.cubeStart := true.B
      when(io.cubeDone) {
        pc := pc + 1.U
        state := sFetch
      }
    }

    is(sVec) {
      io.vectorStart := true.B
      io.vectorOp := Mux(opLat === Opcode.RELU, 1.U, 0.U)
      io.vectorSrc1Addr := vecSrc1Lat
      io.vectorSrc2Addr := vecSrc2Lat
      when(io.vectorDone) {
        pc := pc + 1.U
        state := sFetch
      }
    }

    is(sDmaWait) {
      when(io.dmaQueueEmpty) {
        pc := pc + 1.U
        state := sFetch
      }
    }

    is(sHalted) {}
  }
}
