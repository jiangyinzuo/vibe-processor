package ascend

import chisel3._

/** Performance counters for cycle-level profiling. Pure observation, no functional impact. */
class PerfCounters extends Bundle {
  val totalCycles = UInt(32.W)
  // SPMD block execution
  val blockStarts = UInt(16.W)
  val blockCompletions = UInt(16.W)
  val activeBlockCycles = UInt(32.W)
  // Instruction counts by type
  val instrNop = UInt(16.W)
  val instrHalt = UInt(16.W)
  val instrLoad = UInt(16.W)
  val instrStore = UInt(16.W)
  val instrMatmul = UInt(16.W)
  val instrVecadd = UInt(16.W)
  val instrRelu = UInt(16.W)
  // Cube (MATMUL) profiling
  val cubeTotalCycles = UInt(32.W)
  val cubeComputeCycles = UInt(32.W)
  // Pipeline bubbles
  val bubbleCycles = UInt(32.W)
  // Memory access counts
  val ubReads = UInt(16.W)
  val ubWrites = UInt(16.W)
  // Vector operation counts
  val vecaddCount = UInt(16.W)
  val reluCount = UInt(16.W)
  // DMA profiling
  val dmaLoadCount = UInt(16.W)
  val dmaStoreCount = UInt(16.W)
  val dmaLoadTaskCount = UInt(16.W)
  val dmaStoreTaskCount = UInt(16.W)
  val dmaTotalCycles = UInt(32.W)
  // Ascend-style dataflow profiling: CopyIn (MTE1), external DMA (MTE2), CopyOut (MTE3)
  val copyInTaskCount = UInt(16.W)
  val copyOutTaskCount = UInt(16.W)
  val copyInCycles = UInt(32.W)
  val copyOutCycles = UInt(32.W)
  val copyInComputeOverlapCycles = UInt(32.W)
  val dmaComputeOverlapCycles = UInt(32.W)
  val copyOutComputeOverlapCycles = UInt(32.W)
  val dataflowOverlapCycles = UInt(32.W)
  // Event/token wait profiling
  val waitAllCycles = UInt(32.W)
  val waitDmaCycles = UInt(32.W)
  val waitCopyInCycles = UInt(32.W)
  val waitCopyOutCycles = UInt(32.W)
  // Legacy overlap profiling (Cube compute + MTE1/MTE2 overlap)
  val overlapCycles = UInt(32.W)
}
