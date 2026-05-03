package gpu

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec

/** 快速验证共享架构的基本功能 */
class QuickSharedArchTest extends AnyFunSpec with ChiselSim {

  val W = GpuParams.WarpWidth
  val DW = GpuParams.DataWidth

  def enc(op: Int, rd: Int = 0, rs1: Int = 0, rs2: Int = 0, rs3: Int = 0, imm: Int = 0): Long =
    ((op & 0xf).toLong << 28) | ((rd & 0xf).toLong << 24) | ((rs1 & 0xf).toLong << 20) |
      ((rs2 & 0xf).toLong << 16) | ((rs3 & 0xf).toLong << 12) | (imm & 0xfff).toLong

  describe("Quick Shared Architecture Test") {

    it("simple ADD program") {
      simulate(new ToyGpuTop(numSMs = 1, gmemLatency = 1)) { dut =>
        // 初始化
        dut.io.start.poke(false.B)
        dut.io.imemLoadEn.poke(false.B)
        dut.io.gmemExt.en.poke(false.B)
        dut.clock.step()

        // 写入测试数据
        dut.io.gmemExt.en.poke(true.B)
        dut.io.gmemExt.we.poke(true.B)
        dut.io.gmemExt.addr.poke(0.U)
        for (i <- 0 until W) dut.io.gmemExt.wdata(i).poke((10 * (i + 1)).S)
        dut.clock.step()

        dut.io.gmemExt.addr.poke(1.U)
        for (i <- 0 until W) dut.io.gmemExt.wdata(i).poke((i + 1).S)
        dut.clock.step()

        dut.io.gmemExt.en.poke(false.B)
        dut.io.gmemExt.we.poke(false.B)
        dut.clock.step()

        // 加载程序
        val program = Seq(
          enc(0x2, rd = 0, rs1 = 15, imm = 0), // LD R0, [R15+0]
          enc(0x2, rd = 1, rs1 = 15, imm = 1), // LD R1, [R15+1]
          enc(0x4, rd = 2, rs1 = 0, rs2 = 1), // ADD R2, R0, R1
          enc(0x3, rs1 = 15, rs2 = 2, imm = 2), // ST [R15+2], R2
          enc(0x1) // HALT
        )

        for ((instr, i) <- program.zipWithIndex) {
          dut.io.imemLoadEn.poke(true.B)
          dut.io.imemLoadAddr.poke(i.U)
          dut.io.imemLoadData.poke(instr.U)
          dut.clock.step()
        }
        dut.io.imemLoadEn.poke(false.B)
        dut.clock.step()

        // 启动
        dut.io.start.poke(true.B)
        dut.clock.step()
        dut.io.start.poke(false.B)

        // 运行到 halt
        var cycles = 0
        while (!dut.io.allHalted.peek().litToBoolean && cycles < 100) {
          dut.clock.step()
          cycles += 1
        }

        println(s"Halted after $cycles cycles")
        assert(dut.io.allHalted.peek().litToBoolean, s"Did not halt within 100 cycles")

        // 读取结果
        dut.io.gmemExt.en.poke(true.B)
        dut.io.gmemExt.we.poke(false.B)
        dut.io.gmemExt.addr.poke(2.U)
        dut.clock.step()
        val result = Array.tabulate(W)(i => dut.io.gmemExt.rdata(i).peek().litValue.toInt)
        dut.io.gmemExt.en.poke(false.B)

        println(s"Result: ${result.mkString(", ")}")
        println(s"Expected: 11, 22, 33, 44")

        // 验证
        for (i <- 0 until W) {
          assert(
            result(i) == (10 * (i + 1) + (i + 1)),
            s"Lane $i: got ${result(i)}, expected ${10 * (i + 1) + (i + 1)}"
          )
        }
      }
    }
  }
}
