package ascend

import chisel3._

/** Global parameters for the Toy Ascend NPU. */
object AscendParams {
  val DataWidth  = 8
  val AccWidth   = 32
  val ArraySize  = 4
  val InstrWidth = 32
  val UBDepth    = 1024
  val UBAddrW    = 16
  val IMEMDepth  = 256
}
