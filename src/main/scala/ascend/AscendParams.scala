package ascend

import chisel3._

/** Global parameters for the Toy Ascend NPU. */
object AscendParams {
  val DataWidth = 8
  val AccWidth = 32
  val FractalTileSize = 16
  val ArraySize = FractalTileSize // Cube tile is one 16×16 fractal block (256 PEs)
  val PeMacLatency = 2 // PE multiply and add are split into two pipeline stages
  val InstrWidth = 32
  val NumCores = 2
  val BlockDimWidth = 16
  // Per-core UB (on-chip, private)
  val UBDepth = 256
  val UBAddrW = 16
  // Shared L2 Buffer (on-chip, multi-core shared)
  val L2Depth = 2048
  val L2AddrW = 16
  // HBM (off-chip)
  val HBMDepth = 4096
  val HBMAddrW = 16
  val HBMLatency = 10
  // Instruction memory (shared)
  val IMEMDepth = 256
  // Per-core L2 address slice size
  val L2SliceSize = L2Depth / NumCores // 1024 per core

  // L0 缓存配置（新增：L0A/L0B/L0C 分离）
  val L0ADepth = ArraySize // L0A: 激活输入缓存，存储 N 行
  val L0BDepth = ArraySize // L0B: 权重输入缓存，存储 N 行
  val L0CDepth = ArraySize // L0C: 输出缓存，存储 N 行
  val CubeTileSlots = 4 // CubeCore-side L0 tile FIFO slots for pipelined LOAD/MATMUL
}
