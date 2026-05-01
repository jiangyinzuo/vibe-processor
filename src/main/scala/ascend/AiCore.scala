package ascend

import chisel3._
import chisel3.util._

/** AI Core: integrates Scalar Unit, Cube Unit, Vector Unit, DMA Engine.
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
    // UB port A (Scalar LOAD/STORE)
    val ubEn     = Output(Bool())
    val ubWe     = Output(Bool())
    val ubAddr   = Output(UInt(AscendParams.UBAddrW.W))
    val ubWdata  = Output(Vec(n, SInt(aw.W)))
    val ubRdata  = Input(Vec(n, SInt(aw.W)))
    // UB port B (DMA)
    val ubEnB    = Output(Bool())
    val ubWeB    = Output(Bool())
    val ubAddrB  = Output(UInt(AscendParams.UBAddrW.W))
    val ubWdataB = Output(Vec(n, SInt(aw.W)))
    val ubRdataB = Input(Vec(n, SInt(aw.W)))
    // L2 port (DMA ↔ shared L2Buffer)
    val l2En     = Output(Bool())
    val l2We     = Output(Bool())
    val l2Addr   = Output(UInt(AscendParams.L2AddrW.W))
    val l2Wdata  = Output(Vec(n, SInt(aw.W)))
    val l2Rdata  = Input(Vec(n, SInt(aw.W)))
    // Performance counters
    val perf     = Output(new PerfCounters)
  })

  val scalar = Module(new ScalarUnit(n, dw, aw))
  val cube   = Module(new CubeUnit(n, dw, aw))
  val vector = Module(new VectorUnit(n, aw))

  // DMA engine — simplified for L2↔UB (no valid/ready, single-cycle L2 access)
  // We keep the DmaEngine FSM but connect its HBM port directly to L2 (1-cycle read)

  // Scalar <-> external
  scalar.io.start    := io.start
  io.halted          := scalar.io.halted
  io.imemAddr        := scalar.io.imemAddr
  scalar.io.imemData := io.imemData

  // Scalar <-> Cube
  cube.io.start        := scalar.io.cubeStart
  scalar.io.cubeDone   := cube.io.done
  cube.io.weightData   := scalar.io.cubeWeight
  cube.io.actData      := scalar.io.cubeAct
  scalar.io.cubeResult := cube.io.result

  // Scalar <-> Vector
  vector.io.start    := scalar.io.vecStart
  scalar.io.vecDone  := vector.io.done
  vector.io.op       := scalar.io.vecOp
  vector.io.src1     := scalar.io.vecSrc1
  vector.io.src2     := scalar.io.vecSrc2
  scalar.io.vecDst   := vector.io.dst

  // === DMA Queue: FIFO for non-blocking DMA requests ===
  val dmaQueueDepth = 4
  val dmaQueue = RegInit(VecInit.fill(dmaQueueDepth)(0.U.asTypeOf(new Bundle {
    val isStore = Bool()
    val hbmAddr = UInt(AscendParams.HBMAddrW.W)
    val ubAddr  = UInt(AscendParams.UBAddrW.W)
  })))
  val dmaQueueValid = RegInit(VecInit.fill(dmaQueueDepth)(false.B))
  val dmaQueueHead  = RegInit(0.U(log2Ceil(dmaQueueDepth).W))
  val dmaQueueTail  = RegInit(0.U(log2Ceil(dmaQueueDepth).W))

  val dmaQueueEmpty = !dmaQueueValid.asUInt.orR
  val dmaQueueFull  = dmaQueueValid.asUInt.andR

  // Connect queue status to Scalar
  scalar.io.dmaQueueEmpty := dmaQueueEmpty
  scalar.io.dmaQueueFull  := dmaQueueFull

  // Enqueue logic
  when(scalar.io.dmaQueueEnq && !dmaQueueFull) {
    dmaQueue(dmaQueueTail).isStore := scalar.io.dmaEnqIsStore
    dmaQueue(dmaQueueTail).hbmAddr := scalar.io.dmaEnqHbmAddr
    dmaQueue(dmaQueueTail).ubAddr  := scalar.io.dmaEnqUbAddr
    dmaQueueValid(dmaQueueTail)    := true.B
    dmaQueueTail := (dmaQueueTail + 1.U) % dmaQueueDepth.U
  }

  // === DMA: L2 ↔ UB (queue-driven FSM) ===

  val sDmaIdle :: sDmaLoadRd :: sDmaLoadWait :: sDmaLoadWb :: sDmaStoreRd :: sDmaStoreWait :: sDmaStoreWr :: sDmaDone :: Nil = Enum(8)
  val dmaState  = RegInit(sDmaIdle)
  val dmaRowCnt = RegInit(0.U(log2Ceil(n + 1).W))
  val dmaL2Base = RegInit(0.U(AscendParams.L2AddrW.W))
  val dmaUbBase  = RegInit(0.U(AscendParams.UBAddrW.W))
  val dmaIsStore = RegInit(false.B)
  val dmaL2DataLat = RegInit(VecInit.fill(n)(0.S(aw.W)))

  // L2 address offset: coreId * L2SliceSize
  val l2Offset = (coreId * AscendParams.L2SliceSize).U(AscendParams.L2AddrW.W)

  // Default L2 signals
  val dmaL2En    = WireDefault(false.B)
  val dmaL2We    = WireDefault(false.B)
  val dmaL2Addr  = WireDefault(0.U(AscendParams.L2AddrW.W))
  val dmaL2Wdata = WireDefault(VecInit.fill(n)(0.S(aw.W)))

  // Default DMA UB signals (will connect to UB portB)
  val dmaUbEn    = WireDefault(false.B)
  val dmaUbWe    = WireDefault(false.B)
  val dmaUbAddr  = WireDefault(0.U(AscendParams.UBAddrW.W))
  val dmaUbWdata = WireDefault(VecInit.fill(n)(0.S(aw.W)))

  // Connect DMA to UB port B early (before switch statement)
  io.ubEnB    := dmaUbEn
  io.ubWeB    := dmaUbWe
  io.ubAddrB  := dmaUbAddr
  io.ubWdataB := dmaUbWdata
  val dmaUbRdata = io.ubRdataB

  switch(dmaState) {
    is(sDmaIdle) {
      when(!dmaQueueEmpty) {
        // Dequeue and start processing
        val req = dmaQueue(dmaQueueHead)
        dmaL2Base  := req.hbmAddr +& l2Offset  // "hbmAddr" field repurposed as L2 offset
        dmaUbBase  := req.ubAddr
        dmaIsStore := req.isStore
        dmaRowCnt  := 0.U
        dmaState   := Mux(req.isStore, sDmaStoreRd, sDmaLoadRd)
      }
    }

    // DMA_LOAD: L2 → UB
    is(sDmaLoadRd) {
      dmaL2En   := true.B
      dmaL2Addr := dmaL2Base + dmaRowCnt
      dmaState  := sDmaLoadWait
    }
    is(sDmaLoadWait) {
      // L2 is Mem (combinational read), so hold addr and latch data
      dmaL2Addr := dmaL2Base + dmaRowCnt
      dmaL2DataLat := io.l2Rdata
      dmaState  := sDmaLoadWb
    }
    is(sDmaLoadWb) {
      dmaUbEn    := true.B
      dmaUbWe    := true.B
      dmaUbAddr  := dmaUbBase + dmaRowCnt
      dmaUbWdata := dmaL2DataLat
      dmaRowCnt  := dmaRowCnt + 1.U
      dmaState   := Mux(dmaRowCnt === (n - 1).U, sDmaDone, sDmaLoadRd)
    }

    // DMA_STORE: UB → L2
    is(sDmaStoreRd) {
      dmaUbEn   := true.B
      dmaUbAddr := dmaUbBase + dmaRowCnt
      dmaState  := sDmaStoreWait
    }
    is(sDmaStoreWait) {
      dmaUbEn   := true.B
      dmaUbAddr := dmaUbBase + dmaRowCnt
      dmaState  := sDmaStoreWr
    }
    is(sDmaStoreWr) {
      dmaUbEn    := true.B
      dmaUbAddr  := dmaUbBase + dmaRowCnt
      dmaL2En    := true.B
      dmaL2We    := true.B
      dmaL2Addr  := dmaL2Base + dmaRowCnt
      dmaL2Wdata := dmaUbRdata  // Read from UB port B
      dmaRowCnt  := dmaRowCnt + 1.U
      dmaState   := Mux(dmaRowCnt === (n - 1).U, sDmaDone, sDmaStoreRd)
    }

    is(sDmaDone) {
      // Dequeue completed request
      dmaQueueValid(dmaQueueHead) := false.B
      dmaQueueHead := (dmaQueueHead + 1.U) % dmaQueueDepth.U
      dmaState := sDmaIdle
    }
  }

  // UB Port A: Scalar LOAD/STORE (UB ↔ L0)
  // UB Port B: DMA (L2 ↔ UB) - already connected above
  // This allows simultaneous Scalar and DMA access to UB
  io.ubEn    := scalar.io.ubEn
  io.ubWe    := scalar.io.ubWe
  io.ubAddr  := scalar.io.ubAddr
  io.ubWdata := scalar.io.ubWdata
  scalar.io.ubRdata := io.ubRdata

  // L2 port
  io.l2En    := dmaL2En
  io.l2We    := dmaL2We
  io.l2Addr  := dmaL2Addr
  io.l2Wdata := dmaL2Wdata

  // --- Performance Counters ---
  val perf = RegInit(0.U.asTypeOf(new PerfCounters))
  io.perf := perf

  val running = RegInit(false.B)
  when(io.start)  { running := true.B }
  when(io.halted) { running := false.B }

  when(running && !io.halted) {
    perf.totalCycles := perf.totalCycles + 1.U
  }

  // Instruction counts — sDecode = state 2
  val wasInDecode = RegNext(scalar.io.dbgState === 2.U, false.B)
  when(wasInDecode) {
    switch(scalar.io.dbgOpLat) {
      is(Opcode.NOP)       { perf.instrNop       := perf.instrNop       + 1.U }
      is(Opcode.HALT)      { perf.instrHalt      := perf.instrHalt      + 1.U }
      is(Opcode.LOAD)      { perf.instrLoad      := perf.instrLoad      + 1.U }
      is(Opcode.STORE)     { perf.instrStore     := perf.instrStore     + 1.U }
      is(Opcode.MATMUL)    { perf.instrMatmul    := perf.instrMatmul    + 1.U }
      is(Opcode.VECADD)    { perf.instrVecadd    := perf.instrVecadd    + 1.U }
      is(Opcode.RELU)      { perf.instrRelu      := perf.instrRelu      + 1.U }
      is(Opcode.DMA_LOAD)  { perf.dmaLoadCount   := perf.dmaLoadCount   + 1.U }
      is(Opcode.DMA_STORE) { perf.dmaStoreCount  := perf.dmaStoreCount  + 1.U }
    }
  }

  // Cube profiling
  val cubeActive = RegInit(false.B)
  when(scalar.io.cubeStart) { cubeActive := true.B }
  when(cube.io.done)        { cubeActive := false.B }
  when(cubeActive) { perf.cubeTotalCycles := perf.cubeTotalCycles + 1.U }
  when(cube.io.dbgFeeding) { perf.cubeComputeCycles := perf.cubeComputeCycles + 1.U }

  // Bubbles: sMatmul=8, sVec=9, sDmaWait=10
  val scalarWaiting = running && (
    (scalar.io.dbgState === 8.U && !scalar.io.cubeStart) ||
    (scalar.io.dbgState === 9.U && !scalar.io.vecStart) ||
    (scalar.io.dbgState === 10.U && dmaQueueEmpty)  // Updated: wait for queue empty
  )
  when(scalarWaiting) { perf.bubbleCycles := perf.bubbleCycles + 1.U }

  // UB access
  when(scalar.io.ubEn && !scalar.io.ubWe) { perf.ubReads  := perf.ubReads  + 1.U }
  when(scalar.io.ubEn && scalar.io.ubWe)  { perf.ubWrites := perf.ubWrites + 1.U }

  // Vector ops
  when(scalar.io.vecStart && scalar.io.vecOp === 0.U) { perf.vecaddCount := perf.vecaddCount + 1.U }
  when(scalar.io.vecStart && scalar.io.vecOp === 1.U) { perf.reluCount   := perf.reluCount   + 1.U }

  // DMA cycles
  val dmaRunning = dmaState =/= sDmaIdle && dmaState =/= sDmaDone
  when(dmaRunning) { perf.dmaTotalCycles := perf.dmaTotalCycles + 1.U }

  // Overlap cycles: compute and DMA running simultaneously
  when(cubeActive && dmaRunning) {
    perf.overlapCycles := perf.overlapCycles + 1.U
  }
}
