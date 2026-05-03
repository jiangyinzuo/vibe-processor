package ascend

import chisel3._
import chisel3.util._

/** Instruction opcodes. */
object Opcode {
  val NOP = 0x0.U(4.W)
  val HALT = 0x1.U(4.W)
  val LOAD = 0x2.U(4.W)
  val STORE = 0x3.U(4.W)
  val MATMUL = 0x4.U(4.W)
  val VECADD = 0x5.U(4.W)
  val RELU = 0x6.U(4.W)
  val DMA_LOAD = 0x8.U(4.W)
  val DMA_STORE = 0x9.U(4.W)
  val WAIT = 0xa.U(4.W)
  val DMA_WAIT = WAIT
}

/** WAIT event selector encoded in bits [27:26]. */
object WaitKind {
  val ALL = 0.U(2.W)
  val DMA = 1.U(2.W)
  val COPY_IN = 2.U(2.W)
  val COPY_OUT = 3.U(2.W)
}

/** Stable debug-state numbers used by performance counters. */
object ScalarDbgState {
  val Decode = 2.U(4.W)
  val Matmul = 3.U(4.W)
  val Vector = 4.U(4.W)
  val Wait = 5.U(4.W)
}

/** Buffer select for LOAD/STORE. Existing encodings are preserved. */
object BufSel {
  val L0_A = 0.U(2.W)
  val L0_B = 1.U(2.W)
  val L0_C = 2.U(2.W)
  val VEC = 3.U(2.W)
}

/** Scalar Unit: instruction fetch, decode, and command dispatch.
  *
  * The scalar pipeline does not directly drive MTE engines. It issues dataflow tasks into queues
  * and uses event tokens to wait only for the producer stage required by the next consumer.
  */
class ScalarUnit(
    n: Int = AscendParams.ArraySize,
    dw: Int = AscendParams.DataWidth,
    aw: Int = AscendParams.AccWidth
) extends Module {
  val io = IO(new Bundle {
    val start = Input(Bool())
    val halted = Output(Bool())

    val imemAddr = Output(UInt(8.W))
    val imemData = Input(UInt(AscendParams.InstrWidth.W))

    val copyInQueueEnq = Output(Bool())
    val copyInQueueFull = Input(Bool())
    val copyInDstSel = Output(UInt(2.W))
    val copyInUbAddr = Output(UInt(AscendParams.UBAddrW.W))

    val copyOutQueueEnq = Output(Bool())
    val copyOutQueueFull = Input(Bool())
    val copyOutUbAddr = Output(UInt(AscendParams.UBAddrW.W))

    val cubeStart = Output(Bool())
    val cubeAccumulate = Output(Bool())
    val cubeDone = Input(Bool())

    val vectorStart = Output(Bool())
    val vectorDone = Input(Bool())
    val vectorOp = Output(UInt(2.W))
    val vectorSrc1Addr = Output(UInt(AscendParams.UBAddrW.W))
    val vectorSrc2Addr = Output(UInt(AscendParams.UBAddrW.W))

    val dmaQueueEnq = Output(Bool())
    val dmaQueueFull = Input(Bool())
    val dmaEnqIsStore = Output(Bool())
    val dmaEnqL2Addr = Output(UInt(AscendParams.L2AddrW.W))
    val dmaEnqUbAddr = Output(UInt(AscendParams.UBAddrW.W))

    val waitAllPending = Input(Bool())
    val waitDmaPending = Input(Bool())
    val waitCopyInPending = Input(Bool())
    val waitCopyOutPending = Input(Bool())

    val dbgState = Output(UInt(4.W))
    val dbgOpLat = Output(UInt(4.W))
    val dbgWaitKind = Output(UInt(2.W))
  })

  val sIdle :: sFetch :: sDecode :: sMatmul :: sVec :: sWait :: sHalted :: Nil = Enum(7)

  val state = RegInit(sIdle)
  val pc = RegInit(0.U(8.W))

  val opLat = RegInit(0.U(4.W))
  val dstSelLat = RegInit(0.U(2.W))
  val memAddrLat = RegInit(0.U(AscendParams.UBAddrW.W))
  val vecSrc1Lat = RegInit(0.U(AscendParams.UBAddrW.W))
  val vecSrc2Lat = RegInit(0.U(AscendParams.UBAddrW.W))
  val cubeAccLat = RegInit(false.B)
  val waitKindLat = RegInit(WaitKind.ALL)

  val instr = io.imemData
  val op = instr(31, 28)
  val dstSel = instr(27, 26)
  val memAddr = instr(19, 4)
  val vecSrc1Addr = instr(27, 22)
  val vecSrc2Addr = instr(21, 16)
  val dmaUbBase = instr(27, 20)
  val dmaL2Base = instr(19, 4)
  val matmulAccumulate = instr(27)
  val waitKind = instr(27, 26)

  io.imemAddr := pc
  io.halted := state === sHalted
  io.dbgState := state.asUInt
  io.dbgOpLat := opLat
  io.dbgWaitKind := waitKindLat

  io.copyInQueueEnq := false.B
  io.copyInDstSel := dstSelLat
  io.copyInUbAddr := memAddrLat

  io.copyOutQueueEnq := false.B
  io.copyOutUbAddr := memAddrLat

  io.cubeStart := false.B
  io.cubeAccumulate := cubeAccLat

  io.vectorStart := false.B
  io.vectorOp := Mux(opLat === Opcode.RELU, 1.U, 0.U)
  io.vectorSrc1Addr := vecSrc1Lat
  io.vectorSrc2Addr := vecSrc2Lat

  io.dmaQueueEnq := false.B
  io.dmaEnqIsStore := false.B
  io.dmaEnqL2Addr := 0.U
  io.dmaEnqUbAddr := 0.U

  val selectedWaitPending = WireDefault(io.waitAllPending)
  when(waitKindLat === WaitKind.DMA) {
    selectedWaitPending := io.waitDmaPending
  }.elsewhen(waitKindLat === WaitKind.COPY_IN) {
    selectedWaitPending := io.waitCopyInPending
  }.elsewhen(waitKindLat === WaitKind.COPY_OUT) {
    selectedWaitPending := io.waitCopyOutPending
  }

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
      cubeAccLat := matmulAccumulate
      waitKindLat := waitKind

      switch(op) {
        is(Opcode.NOP) {
          pc := pc + 1.U
          state := sFetch
        }
        is(Opcode.HALT) {
          state := sHalted
        }
        is(Opcode.LOAD) {
          when(!io.copyInQueueFull) {
            io.copyInQueueEnq := true.B
            io.copyInDstSel := dstSel
            io.copyInUbAddr := memAddr
            pc := pc + 1.U
            state := sFetch
          }
        }
        is(Opcode.STORE) {
          when(!io.copyOutQueueFull) {
            io.copyOutQueueEnq := true.B
            io.copyOutUbAddr := memAddr
            pc := pc + 1.U
            state := sFetch
          }
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
        is(Opcode.WAIT) {
          state := sWait
        }
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

    is(sWait) {
      when(!selectedWaitPending) {
        pc := pc + 1.U
        state := sFetch
      }
    }

    is(sHalted) {
      when(io.start) {
        pc := 0.U
        state := sFetch
      }
    }
  }
}
