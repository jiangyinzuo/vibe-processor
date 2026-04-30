package ascend

import chisel3._

/** Global parameters for the Toy Ascend NPU. */
object AscendParams {
  val DataWidth  = 8
  val AccWidth   = 32
  val ArraySize  = 4
  val InstrWidth = 32
  val NumCores   = 2
  // Per-core UB (on-chip, private)
  val UBDepth    = 256
  val UBAddrW    = 16
  // Shared L2 Buffer (on-chip, multi-core shared)
  val L2Depth    = 2048
  val L2AddrW    = 16
  // HBM (off-chip)
  val HBMDepth   = 4096
  val HBMAddrW   = 16
  val HBMLatency = 10
  // Instruction memory (shared)
  val IMEMDepth  = 256
  // Per-core L2 address slice size
  val L2SliceSize = L2Depth / NumCores  // 1024 per core
}
