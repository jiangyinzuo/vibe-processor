package ascend

import chisel3._
import chisel3.util._

/** AI Core: integrates Scalar Unit, Cube Unit, Vector Unit, DMA Engine. */
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
    // UB port (directly to UB, arbitrated between Scalar and DMA)
    val ubEn     = Output(Bool())
    val ubWe     = Output(Bool())
    val ubAddr   = Output(UInt(AscendParams.UBAddrW.W))
    val ubWdata  = Output(Vec(n, SInt(aw.W)))
    val ubRdata  = Input(Vec(n, SInt(aw.W)))
    // HBM port (to LatencyMem)
    val hbmReqValid  = Output(Bool())
    val hbmReqReady  = Input(Bool())
    val hbmReqWe     = Output(Bool())
    val hbmReqAddr   = Output(UInt(AscendParams.HBMAddrW.W))
    val hbmReqWdata  = Output(Vec(n, SInt(aw.W)))
    val hbmRespValid = Input(Bool())
    val hbmRespRdata = Input(Vec(n, SInt(aw.W)))
    // Performance counters
    val perf     = Output(new PerfCounters)
  })

  val scalar = Module(new ScalarUnit(n, dw, aw))
  val cube   = Module(new CubeUnit(n, dw, aw))
  val vector = Module(new VectorUnit(n, aw))
  val dma    = Module(new DmaEngine(n, aw))

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

  // Scalar <-> DMA
  dma.io.start   := scalar.io.dmaStart
  dma.io.isStore := scalar.io.dmaIsStore
  dma.io.hbmAddr := scalar.io.dmaHbmAddr
  dma.io.ubAddr  := scalar.io.dmaUbAddr
  scalar.io.dmaDone := dma.io.done

  // DMA <-> HBM
  io.hbmReqValid  := dma.io.hbmReqValid
  io.hbmReqWe     := dma.io.hbmReqWe
  io.hbmReqAddr   := dma.io.hbmReqAddr
  io.hbmReqWdata  := dma.io.hbmReqWdata
  dma.io.hbmReqReady  := io.hbmReqReady
  dma.io.hbmRespValid := io.hbmRespValid
  dma.io.hbmRespRdata := io.hbmRespRdata

  // UB Port A arbitration: DMA has priority when active, else Scalar
  val dmaActive = dma.io.ubEn
  io.ubEn    := Mux(dmaActive, dma.io.ubEn,       scalar.io.ubEn)
  io.ubWe    := Mux(dmaActive, dma.io.ubWe,        scalar.io.ubWe)
  io.ubAddr  := Mux(dmaActive, dma.io.ubPortAddr,  scalar.io.ubAddr)
  io.ubWdata := Mux(dmaActive, dma.io.ubWdata,     scalar.io.ubWdata)
  // Both Scalar and DMA can read UB rdata
  scalar.io.ubRdata := io.ubRdata
  dma.io.ubRdata    := io.ubRdata

  // --- Performance Counters ---
  val perf = RegInit(0.U.asTypeOf(new PerfCounters))
  io.perf := perf

  val running = RegInit(false.B)
  when(io.start)  { running := true.B }
  when(io.halted) { running := false.B }

  // 1. Total cycles
  when(running && !io.halted) {
    perf.totalCycles := perf.totalCycles + 1.U
  }

  // 2. Instruction counts — detect leaving sDecode (state enum value 2)
  // ScalarUnit Enum: sIdle=0, sFetch=1, sDecode=2, sLoad0=3, ..., sDmaWait=10, sHalted=11
  val wasInDecode = RegNext(scalar.io.dbgState === 2.U, false.B)
  when(wasInDecode) {
    switch(scalar.io.dbgOpLat) {
      is(Opcode.NOP)       { perf.instrNop    := perf.instrNop    + 1.U }
      is(Opcode.HALT)      { perf.instrHalt   := perf.instrHalt   + 1.U }
      is(Opcode.LOAD)      { perf.instrLoad   := perf.instrLoad   + 1.U }
      is(Opcode.STORE)     { perf.instrStore  := perf.instrStore  + 1.U }
      is(Opcode.MATMUL)    { perf.instrMatmul := perf.instrMatmul + 1.U }
      is(Opcode.VECADD)    { perf.instrVecadd := perf.instrVecadd + 1.U }
      is(Opcode.RELU)      { perf.instrRelu   := perf.instrRelu   + 1.U }
      is(Opcode.DMA_LOAD)  { perf.dmaLoadCount  := perf.dmaLoadCount  + 1.U }
      is(Opcode.DMA_STORE) { perf.dmaStoreCount := perf.dmaStoreCount + 1.U }
    }
  }

  // 3. Cube profiling
  val cubeActive = RegInit(false.B)
  when(scalar.io.cubeStart) { cubeActive := true.B }
  when(cube.io.done)        { cubeActive := false.B }
  when(cubeActive) { perf.cubeTotalCycles := perf.cubeTotalCycles + 1.U }
  when(cube.io.dbgFeeding) { perf.cubeComputeCycles := perf.cubeComputeCycles + 1.U }

  // 4. Bubble cycles: scalar waiting for cube/vector/DMA
  // sMatmul=8, sVec=9, sDmaWait=10
  val scalarWaiting = running && (
    (scalar.io.dbgState === 8.U && !scalar.io.cubeStart) ||
    (scalar.io.dbgState === 9.U && !scalar.io.vecStart) ||
    (scalar.io.dbgState === 10.U && !scalar.io.dmaStart)
  )
  when(scalarWaiting) { perf.bubbleCycles := perf.bubbleCycles + 1.U }

  // 5. UB access counts (Scalar only, DMA UB accesses counted separately)
  when(scalar.io.ubEn && !scalar.io.ubWe) { perf.ubReads  := perf.ubReads  + 1.U }
  when(scalar.io.ubEn && scalar.io.ubWe)  { perf.ubWrites := perf.ubWrites + 1.U }

  // 6. Vector ops
  when(scalar.io.vecStart && scalar.io.vecOp === 0.U) { perf.vecaddCount := perf.vecaddCount + 1.U }
  when(scalar.io.vecStart && scalar.io.vecOp === 1.U) { perf.reluCount   := perf.reluCount   + 1.U }

  // 7. DMA cycles
  val dmaRunning = RegInit(false.B)
  when(scalar.io.dmaStart) { dmaRunning := true.B }
  when(dma.io.done)        { dmaRunning := false.B }
  when(dmaRunning) { perf.dmaTotalCycles := perf.dmaTotalCycles + 1.U }
}
