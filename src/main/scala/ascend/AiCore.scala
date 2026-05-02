package ascend

import chisel3._
import chisel3.util._

/** AI Core: scalar dispatcher + decoupled AIC/AIV compute cores + MTEs.
  *
  * @param coreId Used for L2 address offset in data-parallel mode.
  */
class AiCore(
    n:      Int = AscendParams.ArraySize,
    dw:     Int = AscendParams.DataWidth,
    aw:     Int = AscendParams.AccWidth,
    coreId: Int = 0
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

    val ubEnB    = Output(Bool())
    val ubWeB    = Output(Bool())
    val ubAddrB  = Output(UInt(AscendParams.UBAddrW.W))
    val ubWdataB = Output(Vec(n, SInt(aw.W)))
    val ubRdataB = Input(Vec(n, SInt(aw.W)))

    val l2En     = Output(Bool())
    val l2We     = Output(Bool())
    val l2Addr   = Output(UInt(AscendParams.L2AddrW.W))
    val l2Wdata  = Output(Vec(n, SInt(aw.W)))
    val l2Rdata  = Input(Vec(n, SInt(aw.W)))

    val perf     = Output(new PerfCounters)
  })

  val scalar = Module(new ScalarUnit(n, dw, aw))
  val aic    = Module(new AicCore(n, dw, aw))
  val aiv    = Module(new AivCore(n, aw))
  val mte1   = Module(new Mte1(n, dw, aw))
  val mte2   = Module(new Mte2(n, aw))
  val mte3   = Module(new Mte3(n, aw))

  scalar.io.start := io.start
  io.halted := scalar.io.halted
  io.imemAddr := scalar.io.imemAddr
  scalar.io.imemData := io.imemData

  // === Scalar -> AIC/AIV/MTE command dispatch ===
  mte1.io.start := scalar.io.mte1Start
  mte1.io.dstSel := scalar.io.mte1DstSel
  mte1.io.ubBase := scalar.io.mte1UbAddr
  scalar.io.mte1Busy := mte1.io.busy
  scalar.io.mte1Done := mte1.io.done

  mte3.io.start := scalar.io.mte3Start
  mte3.io.ubBase := scalar.io.mte3UbAddr
  scalar.io.mte3Done := mte3.io.done

  aic.io.start := scalar.io.aicStart
  scalar.io.aicDone := aic.io.done

  aiv.io.start := scalar.io.aivStart
  aiv.io.op := scalar.io.aivOp
  aiv.io.src1Addr := scalar.io.aivSrc1Addr
  aiv.io.src2Addr := scalar.io.aivSrc2Addr
  scalar.io.aivDone := aiv.io.done

  aic.io.mte1Write := mte1.io.aicWrite
  aic.io.l0cReadRow := mte3.io.l0cReadRow
  mte3.io.l0cReadData := aic.io.l0cReadData

  // === DMA queue feeding MTE2 ===
  val dmaQueueDepth = 4
  val dmaQueue = RegInit(VecInit.fill(dmaQueueDepth)(0.U.asTypeOf(new Bundle {
    val isStore = Bool()
    val l2Addr  = UInt(AscendParams.L2AddrW.W)
    val ubAddr  = UInt(AscendParams.UBAddrW.W)
  })))
  val dmaQueueValid = RegInit(VecInit.fill(dmaQueueDepth)(false.B))
  val dmaQueueHead  = RegInit(0.U(log2Ceil(dmaQueueDepth).W))
  val dmaQueueTail  = RegInit(0.U(log2Ceil(dmaQueueDepth).W))

  val dmaQueueEmpty = !dmaQueueValid.asUInt.orR
  val dmaQueueFull  = dmaQueueValid.asUInt.andR

  scalar.io.dmaQueueEmpty := dmaQueueEmpty
  scalar.io.dmaQueueFull := dmaQueueFull

  when(scalar.io.dmaQueueEnq && !dmaQueueFull) {
    dmaQueue(dmaQueueTail).isStore := scalar.io.dmaEnqIsStore
    dmaQueue(dmaQueueTail).l2Addr := scalar.io.dmaEnqL2Addr
    dmaQueue(dmaQueueTail).ubAddr := scalar.io.dmaEnqUbAddr
    dmaQueueValid(dmaQueueTail) := true.B
    dmaQueueTail := (dmaQueueTail + 1.U) % dmaQueueDepth.U
  }

  val l2Offset = (coreId * AscendParams.L2SliceSize).U(AscendParams.L2AddrW.W)
  val dmaReq = dmaQueue(dmaQueueHead)
  val mte2Start = dmaQueueValid(dmaQueueHead) && !mte2.io.busy && !mte2.io.done

  mte2.io.start := mte2Start
  mte2.io.isStore := dmaReq.isStore
  mte2.io.l2Base := dmaReq.l2Addr + l2Offset
  mte2.io.ubBase := dmaReq.ubAddr

  when(mte2.io.done) {
    dmaQueueValid(dmaQueueHead) := false.B
    dmaQueueHead := (dmaQueueHead + 1.U) % dmaQueueDepth.U
  }

  // === UB port A arbitration: MTE1/MTE3/AIV share the local-memory port ===
  val portAUseMte1 = mte1.io.ubEn
  val portAUseMte3 = !portAUseMte1 && mte3.io.ubEn
  val portAUseAiv  = !portAUseMte1 && !portAUseMte3 && aiv.io.ubEn

  io.ubEn := portAUseMte1 || portAUseMte3 || portAUseAiv
  io.ubWe := Mux(portAUseMte3, mte3.io.ubWe, Mux(portAUseAiv, aiv.io.ubWe, false.B))
  io.ubAddr := Mux(portAUseMte1, mte1.io.ubAddr,
    Mux(portAUseMte3, mte3.io.ubAddr,
      Mux(portAUseAiv, aiv.io.ubAddr, 0.U)))
  io.ubWdata := Mux(portAUseMte3, mte3.io.ubWdata,
    Mux(portAUseAiv, aiv.io.ubWdata, VecInit.fill(n)(0.S(aw.W))))

  mte1.io.ubRdata := io.ubRdata
  aiv.io.ubRdata := io.ubRdata

  // === UB port B and L2 are owned by MTE2 ===
  io.ubEnB := mte2.io.ubEn
  io.ubWeB := mte2.io.ubWe
  io.ubAddrB := mte2.io.ubAddr
  io.ubWdataB := mte2.io.ubWdata
  mte2.io.ubRdata := io.ubRdataB

  io.l2En := mte2.io.l2En
  io.l2We := mte2.io.l2We
  io.l2Addr := mte2.io.l2Addr
  io.l2Wdata := mte2.io.l2Wdata
  mte2.io.l2Rdata := io.l2Rdata

  // --- Performance Counters ---
  val perf = RegInit(0.U.asTypeOf(new PerfCounters))
  io.perf := perf

  val running = RegInit(false.B)
  when(io.start)  { running := true.B }
  when(io.halted) { running := false.B }

  when(running && !io.halted) {
    perf.totalCycles := perf.totalCycles + 1.U
  }

  val wasInDecode = RegNext(scalar.io.dbgState === 2.U, false.B)
  when(wasInDecode) {
    switch(scalar.io.dbgOpLat) {
      is(Opcode.NOP)       { perf.instrNop      := perf.instrNop + 1.U }
      is(Opcode.HALT)      { perf.instrHalt     := perf.instrHalt + 1.U }
      is(Opcode.LOAD)      { perf.instrLoad     := perf.instrLoad + 1.U }
      is(Opcode.STORE)     { perf.instrStore    := perf.instrStore + 1.U }
      is(Opcode.MATMUL)    { perf.instrMatmul   := perf.instrMatmul + 1.U }
      is(Opcode.VECADD)    { perf.instrVecadd   := perf.instrVecadd + 1.U }
      is(Opcode.RELU)      { perf.instrRelu     := perf.instrRelu + 1.U }
      is(Opcode.DMA_LOAD)  { perf.dmaLoadCount  := perf.dmaLoadCount + 1.U }
      is(Opcode.DMA_STORE) { perf.dmaStoreCount := perf.dmaStoreCount + 1.U }
    }
  }

  val aicActive = RegInit(false.B)
  when(scalar.io.aicStart) { aicActive := true.B }
  when(aic.io.done)        { aicActive := false.B }
  when(aicActive) { perf.cubeTotalCycles := perf.cubeTotalCycles + 1.U }
  when(aic.io.dbgFeeding) { perf.cubeComputeCycles := perf.cubeComputeCycles + 1.U }

  val scalarWaiting = running && (
    (scalar.io.dbgState === 8.U && !scalar.io.aicStart) ||
      (scalar.io.dbgState === 9.U && !scalar.io.aivStart) ||
      (scalar.io.dbgState === 10.U && dmaQueueEmpty)
  )
  when(scalarWaiting) { perf.bubbleCycles := perf.bubbleCycles + 1.U }

  val ubPortAEn = io.ubEn
  val ubPortAWe = io.ubWe
  val ubPortBEn = io.ubEnB
  val ubPortBWe = io.ubWeB
  when((ubPortAEn && !ubPortAWe) || (ubPortBEn && !ubPortBWe)) {
    perf.ubReads := perf.ubReads + 1.U
  }
  when((ubPortAEn && ubPortAWe) || (ubPortBEn && ubPortBWe)) {
    perf.ubWrites := perf.ubWrites + 1.U
  }

  when(scalar.io.aivStart && scalar.io.aivOp === 0.U) { perf.vecaddCount := perf.vecaddCount + 1.U }
  when(scalar.io.aivStart && scalar.io.aivOp === 1.U) { perf.reluCount := perf.reluCount + 1.U }

  when(mte2.io.busy) { perf.dmaTotalCycles := perf.dmaTotalCycles + 1.U }
  when(aicActive && (mte1.io.busy || mte2.io.busy)) {
    perf.overlapCycles := perf.overlapCycles + 1.U
  }
}
