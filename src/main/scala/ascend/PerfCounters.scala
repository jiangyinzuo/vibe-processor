package ascend

import chisel3._

/** Performance counters for cycle-level profiling. Pure observation, no functional impact. */
class PerfCounters extends Bundle {
  val totalCycles       = UInt(32.W)
  // Instruction counts by type
  val instrNop          = UInt(16.W)
  val instrHalt         = UInt(16.W)
  val instrLoad         = UInt(16.W)
  val instrStore        = UInt(16.W)
  val instrMatmul       = UInt(16.W)
  val instrVecadd       = UInt(16.W)
  val instrRelu         = UInt(16.W)
  // Cube (MATMUL) profiling
  val cubeTotalCycles   = UInt(32.W)
  val cubeComputeCycles = UInt(32.W)
  // Pipeline bubbles
  val bubbleCycles      = UInt(32.W)
  // Memory access counts
  val ubReads           = UInt(16.W)
  val ubWrites          = UInt(16.W)
  // Vector operation counts
  val vecaddCount       = UInt(16.W)
  val reluCount         = UInt(16.W)
  // DMA profiling
  val dmaLoadCount      = UInt(16.W)
  val dmaStoreCount     = UInt(16.W)
  val dmaTotalCycles    = UInt(32.W)
}
