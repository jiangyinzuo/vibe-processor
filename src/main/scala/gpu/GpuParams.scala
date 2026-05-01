package gpu

import common.Params

object GpuParams {
  val DataWidth   = Params.AccWidth  // GPU works on INT32 natively
  val WarpWidth   = 4                // 4 threads per warp (real GPU: 32)
  val NumWarps    = 4                // 4 warps per SM
  val NumSMs      = 4                // 4 SMs (real GPU: 16-128)
  val NumRegs     = 16               // 16 registers per thread
  val InstrWidth  = 32
  val IMEMDepth   = 256
  val GlobalDepth = 4096
  val GlobalAddrW = 16
  val SharedDepth = 256
  val SharedAddrW = 8
}

object GpuOpcode {
  import chisel3._
  val NOP  = 0x0.U(4.W)
  val HALT = 0x1.U(4.W)
  val LD   = 0x2.U(4.W)  // Rd = GlobalMem[Rs1 + imm]
  val ST   = 0x3.U(4.W)  // GlobalMem[Rs1 + imm] = Rs2
  val ADD  = 0x4.U(4.W)  // Rd = Rs1 + Rs2
  val MUL  = 0x5.U(4.W)  // Rd = Rs1 * Rs2
  val MAD  = 0x6.U(4.W)  // Rd = Rs1 * Rs2 + Rs3
  val SHM  = 0x7.U(4.W)  // SharedMem ops (sub-opcode in imm field)
}
