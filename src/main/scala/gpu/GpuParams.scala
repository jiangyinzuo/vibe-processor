package gpu

import common.Params

object GpuParams {
  val DataWidth = Params.AccWidth // GPU works on INT32 natively
  val WarpWidth = 4 // 4 threads per warp (real GPU: 32)
  val MaxCTAsPerSM = 2 // 2 resident CTAs per SM (real GPUs support 16-32+)
  val WarpsPerCTA = 2 // 2 warps per CTA in the toy model
  val ThreadsPerCTA = WarpsPerCTA * WarpWidth
  val NumWarps = MaxCTAsPerSM * WarpsPerCTA
  val NumSMs = 4 // 4 SMs (real GPU: 16-128)
  val NumCTAs = NumSMs * MaxCTAsPerSM
  val CTAIdWidth = 8
  val NumRegs = 16 // 16 registers per thread
  val InstrWidth = 32
  val IMEMDepth = 256
  val GlobalDepth = 4096
  val GlobalAddrW = 16
  val SharedDepth = 256
  val SharedAddrW = 8
}

object GpuSpecialReg {
  val ThreadIdxX = 12 // threadIdx.x within the CTA
  val WarpIdxInCTA = 13 // warp index within the CTA
  val BlockIdxX = 14 // blockIdx.x / CTA ID
  val Zero = 15 // conventional zero/base register used by tests
}

object GpuOpcode {
  import chisel3._
  val NOP = 0x0.U(4.W)
  val HALT = 0x1.U(4.W)
  val LD = 0x2.U(4.W) // Rd = GlobalMem[Rs1 + imm]
  val ST = 0x3.U(4.W) // GlobalMem[Rs1 + imm] = Rs2
  val ADD = 0x4.U(4.W) // Rd = Rs1 + Rs2
  val MUL = 0x5.U(4.W) // Rd = Rs1 * Rs2
  val MAD = 0x6.U(4.W) // Rd = Rs1 * Rs2 + Rs3
  val SHM = 0x7.U(4.W) // SharedMem ops (sub-opcode in imm field)
  val EXP = 0x8.U(4.W) // Rd = e^Rs1 (Special Function Unit)
}
