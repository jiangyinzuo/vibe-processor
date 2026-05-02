package gpu

import chisel3._
import chisel3.util._

/** 共享寄存器文件
  *
  * 真实 GPU 中，寄存器文件是 SM 级别的共享资源，
  * 所有 Warp 的寄存器都存储在同一个物理寄存器文件中。
  *
  * 组织方式：
  *   - 4 个 Warp × 8 个 Lane × 16 个寄存器 = 512 个寄存器
  *   - 索引方式：regs(warpId)(laneId)(regId)
  *
  * 端口设计：
  *   - 16 个读端口（每个 CUDA Core 需要读取 rs1, rs2, rs3）
  *   - 16 个写端口（每个 CUDA Core 可以写回结果）
  *
  * 注意：真实 GPU 的寄存器文件有更复杂的 bank 冲突处理，
  * 这里简化为组合逻辑读、时序逻辑写。
  */
class SharedRegisterFile(
    numWarps: Int = 4,
    warpWidth: Int = 8,
    numRegs: Int = 16,
    dataWidth: Int = 32,
    numPorts: Int = 16  // CUDA Core 数量
) extends Module {
  val io = IO(new Bundle {
    // 读端口（组合逻辑）
    val rdAddr = Input(Vec(numPorts, new Bundle {
      val valid  = Bool()
      val warpId = UInt(log2Ceil(numWarps).W)
      val laneId = UInt(log2Ceil(warpWidth).W)
      val rs1    = UInt(log2Ceil(numRegs).W)
      val rs2    = UInt(log2Ceil(numRegs).W)
      val rs3    = UInt(log2Ceil(numRegs).W)
    }))
    val rdData = Output(Vec(numPorts, new Bundle {
      val rs1 = SInt(dataWidth.W)
      val rs2 = SInt(dataWidth.W)
      val rs3 = SInt(dataWidth.W)
    }))

    // 写端口（时序逻辑）
    val wrAddr = Input(Vec(numPorts, new Bundle {
      val valid  = Bool()
      val warpId = UInt(log2Ceil(numWarps).W)
      val laneId = UInt(log2Ceil(warpWidth).W)
      val rd     = UInt(log2Ceil(numRegs).W)
    }))
    val wrData = Input(Vec(numPorts, SInt(dataWidth.W)))
  })

  // 寄存器文件：4 Warp × 8 Lane × 16 Reg
  val regs = RegInit(VecInit.fill(numWarps, warpWidth, numRegs)(0.S(dataWidth.W)))

  // === 读端口（1 周期延迟）===
  for (i <- 0 until numPorts) {
    val rdRs1 = WireDefault(0.S(dataWidth.W))
    val rdRs2 = WireDefault(0.S(dataWidth.W))
    val rdRs3 = WireDefault(0.S(dataWidth.W))

    when(io.rdAddr(i).valid) {
      val warpId = io.rdAddr(i).warpId
      val laneId = io.rdAddr(i).laneId
      val rs1Data = WireDefault(regs(warpId)(laneId)(io.rdAddr(i).rs1))
      val rs2Data = WireDefault(regs(warpId)(laneId)(io.rdAddr(i).rs2))
      val rs3Data = WireDefault(regs(warpId)(laneId)(io.rdAddr(i).rs3))

      // Forward same-cycle writeback so dependent back-to-back instructions
      // (for example EXP -> ST) observe the just-produced value.
      for (j <- 0 until numPorts) {
        when(
          io.wrAddr(j).valid &&
            io.wrAddr(j).warpId === warpId &&
            io.wrAddr(j).laneId === laneId
        ) {
          when(io.wrAddr(j).rd === io.rdAddr(i).rs1) { rs1Data := io.wrData(j) }
          when(io.wrAddr(j).rd === io.rdAddr(i).rs2) { rs2Data := io.wrData(j) }
          when(io.wrAddr(j).rd === io.rdAddr(i).rs3) { rs3Data := io.wrData(j) }
        }
      }

      rdRs1 := rs1Data
      rdRs2 := rs2Data
      rdRs3 := rs3Data
    }

    io.rdData(i).rs1 := RegNext(rdRs1, 0.S)
    io.rdData(i).rs2 := RegNext(rdRs2, 0.S)
    io.rdData(i).rs3 := RegNext(rdRs3, 0.S)
  }

  // === 写端口（时序逻辑）===
  for (i <- 0 until numPorts) {
    when(io.wrAddr(i).valid) {
      val warpId = io.wrAddr(i).warpId
      val laneId = io.wrAddr(i).laneId
      val rd     = io.wrAddr(i).rd
      regs(warpId)(laneId)(rd) := io.wrData(i)
    }
  }

  // === 写冲突检测（调试用）===
  // 如果多个端口同时写同一个寄存器，发出警告
  if (false) {  // 调试时可以启用
    for (i <- 0 until numPorts) {
      for (j <- i + 1 until numPorts) {
        when(
          io.wrAddr(i).valid && io.wrAddr(j).valid &&
            io.wrAddr(i).warpId === io.wrAddr(j).warpId &&
            io.wrAddr(i).laneId === io.wrAddr(j).laneId &&
            io.wrAddr(i).rd === io.wrAddr(j).rd
        ) {
          printf(
            "WARNING: Write conflict on Warp%d Lane%d Reg%d\n",
            io.wrAddr(i).warpId,
            io.wrAddr(i).laneId,
            io.wrAddr(i).rd
          )
        }
      }
    }
  }
}
