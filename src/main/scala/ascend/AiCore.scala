package ascend

import chisel3._
import chisel3.util._

/** AI Core: scalar dispatcher + decoupled CubeCore/VectorCore compute paths + MTEs.
  *
  * @param coreId
  *   Physical core index, kept for debug/diagram compatibility.
  * @param blockStride
  *   L2 row stride between SPMD logical blocks.
  */
class AiCore(
    n: Int = AscendParams.ArraySize,
    dw: Int = AscendParams.DataWidth,
    aw: Int = AscendParams.AccWidth,
    coreId: Int = 0,
    blockStride: Int = AscendParams.L2SliceSize
) extends Module {
  val io = IO(new Bundle {
    val start = Input(Bool())
    val blockIdx = Input(UInt(AscendParams.BlockDimWidth.W))
    val halted = Output(Bool())
    val imemAddr = Output(UInt(8.W))
    val imemData = Input(UInt(AscendParams.InstrWidth.W))

    val ubEn = Output(Bool())
    val ubWe = Output(Bool())
    val ubAddr = Output(UInt(AscendParams.UBAddrW.W))
    val ubWdata = Output(Vec(n, SInt(aw.W)))
    val ubRdata = Input(Vec(n, SInt(aw.W)))

    val ubEnB = Output(Bool())
    val ubWeB = Output(Bool())
    val ubAddrB = Output(UInt(AscendParams.UBAddrW.W))
    val ubWdataB = Output(Vec(n, SInt(aw.W)))
    val ubRdataB = Input(Vec(n, SInt(aw.W)))

    val l2En = Output(Bool())
    val l2We = Output(Bool())
    val l2Addr = Output(UInt(AscendParams.L2AddrW.W))
    val l2Wdata = Output(Vec(n, SInt(aw.W)))
    val l2Rdata = Input(Vec(n, SInt(aw.W)))

    val perf = Output(new PerfCounters)
  })

  val scalar = Module(new ScalarUnit(n, dw, aw))
  val cubeCore = Module(new CubeCore(n, dw, aw))
  val vectorCore = Module(new VectorCore(n, aw))
  val mte1 = Module(new Mte1(n, dw, aw))
  val mte2 = Module(new Mte2(n, aw))
  val mte3 = Module(new Mte3(n, aw))

  scalar.io.start := io.start
  io.halted := scalar.io.halted
  io.imemAddr := scalar.io.imemAddr
  scalar.io.imemData := io.imemData

  // === Scalar -> CubeCore/VectorCore command dispatch ===
  cubeCore.io.start := scalar.io.cubeStart
  cubeCore.io.accumulate := scalar.io.cubeAccumulate
  scalar.io.cubeDone := cubeCore.io.done

  vectorCore.io.start := scalar.io.vectorStart
  vectorCore.io.op := scalar.io.vectorOp
  vectorCore.io.src1Addr := scalar.io.vectorSrc1Addr
  vectorCore.io.src2Addr := scalar.io.vectorSrc2Addr
  scalar.io.vectorDone := vectorCore.io.done

  cubeCore.io.mte1Write := mte1.io.cubeWrite
  cubeCore.io.l0cReadRow := mte3.io.l0cReadRow
  mte3.io.l0cReadData := cubeCore.io.l0cReadData

  // === Dataflow task queues ===
  val dataflowQueueDepth = 4
  val copyInQueue = Module(new Queue(new CopyInTask, dataflowQueueDepth))
  val dmaQueue = Module(new Queue(new DmaTask, dataflowQueueDepth))
  val copyOutQueue = Module(new Queue(new CopyOutTask, dataflowQueueDepth))

  copyInQueue.io.enq.valid := scalar.io.copyInQueueEnq
  copyInQueue.io.enq.bits.dstSel := scalar.io.copyInDstSel
  copyInQueue.io.enq.bits.ubBase := scalar.io.copyInUbAddr
  scalar.io.copyInQueueFull := !copyInQueue.io.enq.ready

  dmaQueue.io.enq.valid := scalar.io.dmaQueueEnq
  dmaQueue.io.enq.bits.isStore := scalar.io.dmaEnqIsStore
  dmaQueue.io.enq.bits.l2Base := scalar.io.dmaEnqL2Addr
  dmaQueue.io.enq.bits.ubBase := scalar.io.dmaEnqUbAddr
  scalar.io.dmaQueueFull := !dmaQueue.io.enq.ready

  copyOutQueue.io.enq.valid := scalar.io.copyOutQueueEnq
  copyOutQueue.io.enq.bits.ubBase := scalar.io.copyOutUbAddr
  scalar.io.copyOutQueueFull := !copyOutQueue.io.enq.ready

  // MTE1 and MTE3 share this toy core's UB port A, so their task queues arbitrate before launch.
  val ubPortADataMoverIdle = !mte1.io.busy && !mte1.io.done && !mte3.io.busy && !mte3.io.done &&
    !vectorCore.io.busy && !vectorCore.io.done
  val launchCopyIn = copyInQueue.io.deq.valid && ubPortADataMoverIdle
  val launchCopyOut = copyOutQueue.io.deq.valid && ubPortADataMoverIdle && !copyInQueue.io.deq.valid

  copyInQueue.io.deq.ready := launchCopyIn
  copyOutQueue.io.deq.ready := launchCopyOut

  mte1.io.start := launchCopyIn
  mte1.io.dstSel := copyInQueue.io.deq.bits.dstSel
  mte1.io.ubBase := copyInQueue.io.deq.bits.ubBase

  mte3.io.start := launchCopyOut
  mte3.io.ubBase := copyOutQueue.io.deq.bits.ubBase

  val l2OffsetFull = io.blockIdx * blockStride.U(AscendParams.L2AddrW.W)
  val l2Offset = l2OffsetFull(AscendParams.L2AddrW - 1, 0)
  val dmaReq = dmaQueue.io.deq.bits
  val copyInPending = copyInQueue.io.deq.valid || mte1.io.busy || mte1.io.done
  val copyOutPending = copyOutQueue.io.deq.valid || mte3.io.busy || mte3.io.done
  val dmaStoreWaitingForCopyOut = dmaQueue.io.deq.valid && dmaReq.isStore && copyOutPending
  val launchDma =
    dmaQueue.io.deq.valid && !mte2.io.busy && !mte2.io.done && !dmaStoreWaitingForCopyOut

  dmaQueue.io.deq.ready := launchDma
  mte2.io.start := launchDma
  mte2.io.isStore := dmaReq.isStore
  mte2.io.l2Base := dmaReq.l2Base + l2Offset
  mte2.io.ubBase := dmaReq.ubBase

  val dmaPending = dmaQueue.io.deq.valid || mte2.io.busy || mte2.io.done

  scalar.io.waitCopyInPending := copyInPending
  scalar.io.waitDmaPending := dmaPending
  scalar.io.waitCopyOutPending := copyOutPending
  scalar.io.waitAllPending := copyInPending || dmaPending || copyOutPending

  // === UB port A arbitration: MTE1/MTE3/VectorCore share the local-memory port ===
  val portAUseMte1 = mte1.io.ubEn
  val portAUseMte3 = !portAUseMte1 && mte3.io.ubEn
  val portAUseVector = !portAUseMte1 && !portAUseMte3 && vectorCore.io.ubEn

  io.ubEn := portAUseMte1 || portAUseMte3 || portAUseVector
  io.ubWe := Mux(portAUseMte3, mte3.io.ubWe, Mux(portAUseVector, vectorCore.io.ubWe, false.B))
  io.ubAddr := Mux(
    portAUseMte1,
    mte1.io.ubAddr,
    Mux(portAUseMte3, mte3.io.ubAddr, Mux(portAUseVector, vectorCore.io.ubAddr, 0.U))
  )
  io.ubWdata := Mux(
    portAUseMte3,
    mte3.io.ubWdata,
    Mux(portAUseVector, vectorCore.io.ubWdata, VecInit.fill(n)(0.S(aw.W)))
  )

  mte1.io.ubRdata := io.ubRdata
  vectorCore.io.ubRdata := io.ubRdata

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
  when(io.start) {
    running := true.B
  }.elsewhen(io.halted) {
    running := false.B
  }

  when(running && !io.halted) {
    perf.totalCycles := perf.totalCycles + 1.U
    perf.activeBlockCycles := perf.activeBlockCycles + 1.U
  }

  when(io.start) {
    perf.blockStarts := perf.blockStarts + 1.U
  }
  val haltedPrev = RegNext(io.halted, false.B)
  when(io.halted && !haltedPrev) {
    perf.blockCompletions := perf.blockCompletions + 1.U
  }

  val wasInDecode = RegNext(scalar.io.dbgState === ScalarDbgState.Decode, false.B)
  when(wasInDecode) {
    switch(scalar.io.dbgOpLat) {
      is(Opcode.NOP) { perf.instrNop := perf.instrNop + 1.U }
      is(Opcode.HALT) { perf.instrHalt := perf.instrHalt + 1.U }
      is(Opcode.LOAD) { perf.instrLoad := perf.instrLoad + 1.U }
      is(Opcode.STORE) { perf.instrStore := perf.instrStore + 1.U }
      is(Opcode.MATMUL) { perf.instrMatmul := perf.instrMatmul + 1.U }
      is(Opcode.VECADD) { perf.instrVecadd := perf.instrVecadd + 1.U }
      is(Opcode.RELU) { perf.instrRelu := perf.instrRelu + 1.U }
      is(Opcode.DMA_LOAD) { perf.dmaLoadCount := perf.dmaLoadCount + 1.U }
      is(Opcode.DMA_STORE) { perf.dmaStoreCount := perf.dmaStoreCount + 1.U }
    }
  }

  val cubeActive = RegInit(false.B)
  when(scalar.io.cubeStart) { cubeActive := true.B }
  when(cubeCore.io.done) { cubeActive := false.B }
  when(cubeActive) { perf.cubeTotalCycles := perf.cubeTotalCycles + 1.U }
  when(cubeCore.io.dbgFeeding) { perf.cubeComputeCycles := perf.cubeComputeCycles + 1.U }

  val scalarWaiting = running && (
    (scalar.io.dbgState === ScalarDbgState.Matmul && !cubeCore.io.dbgFeeding && !cubeCore.io.done) ||
      (scalar.io.dbgState === ScalarDbgState.Vector && !scalar.io.vectorStart) ||
      (scalar.io.dbgState === ScalarDbgState.Wait && (
        (scalar.io.dbgWaitKind === WaitKind.ALL && scalar.io.waitAllPending) ||
          (scalar.io.dbgWaitKind === WaitKind.DMA && scalar.io.waitDmaPending) ||
          (scalar.io.dbgWaitKind === WaitKind.COPY_IN && scalar.io.waitCopyInPending) ||
          (scalar.io.dbgWaitKind === WaitKind.COPY_OUT && scalar.io.waitCopyOutPending)
      ))
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

  when(scalar.io.vectorStart && scalar.io.vectorOp === 0.U) {
    perf.vecaddCount := perf.vecaddCount + 1.U
  }
  when(scalar.io.vectorStart && scalar.io.vectorOp === 1.U) {
    perf.reluCount := perf.reluCount + 1.U
  }

  val copyInActive = mte1.io.busy
  val dmaActive = mte2.io.busy
  val copyOutActive = mte3.io.busy

  when(launchCopyIn) { perf.copyInTaskCount := perf.copyInTaskCount + 1.U }
  when(launchDma && !dmaReq.isStore) { perf.dmaLoadTaskCount := perf.dmaLoadTaskCount + 1.U }
  when(launchDma && dmaReq.isStore) { perf.dmaStoreTaskCount := perf.dmaStoreTaskCount + 1.U }
  when(launchCopyOut) { perf.copyOutTaskCount := perf.copyOutTaskCount + 1.U }

  when(copyInActive) { perf.copyInCycles := perf.copyInCycles + 1.U }
  when(dmaActive) { perf.dmaTotalCycles := perf.dmaTotalCycles + 1.U }
  when(copyOutActive) { perf.copyOutCycles := perf.copyOutCycles + 1.U }

  when(cubeActive && copyInActive) {
    perf.copyInComputeOverlapCycles := perf.copyInComputeOverlapCycles + 1.U
  }
  when(cubeActive && dmaActive) {
    perf.dmaComputeOverlapCycles := perf.dmaComputeOverlapCycles + 1.U
  }
  when(cubeActive && copyOutActive) {
    perf.copyOutComputeOverlapCycles := perf.copyOutComputeOverlapCycles + 1.U
  }
  when(cubeActive && (copyInActive || dmaActive || copyOutActive)) {
    perf.dataflowOverlapCycles := perf.dataflowOverlapCycles + 1.U
  }

  when(cubeActive && (copyInActive || dmaActive)) {
    perf.overlapCycles := perf.overlapCycles + 1.U
  }

  val waitActive = scalar.io.dbgState === ScalarDbgState.Wait
  when(waitActive && scalar.io.dbgWaitKind === WaitKind.ALL && scalar.io.waitAllPending) {
    perf.waitAllCycles := perf.waitAllCycles + 1.U
  }
  when(waitActive && scalar.io.dbgWaitKind === WaitKind.DMA && scalar.io.waitDmaPending) {
    perf.waitDmaCycles := perf.waitDmaCycles + 1.U
  }
  when(waitActive && scalar.io.dbgWaitKind === WaitKind.COPY_IN && scalar.io.waitCopyInPending) {
    perf.waitCopyInCycles := perf.waitCopyInCycles + 1.U
  }
  when(waitActive && scalar.io.dbgWaitKind === WaitKind.COPY_OUT && scalar.io.waitCopyOutPending) {
    perf.waitCopyOutCycles := perf.waitCopyOutCycles + 1.U
  }
}
