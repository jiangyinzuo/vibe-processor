package gpu

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec

/** GPU 中使用 SFU 的集成测试 */
class SFUIntegrationTest extends AnyFunSpec with ChiselSim {

  val W = GpuParams.WarpWidth
  val DW = GpuParams.DataWidth

  def enc(op: Int, rd: Int = 0, rs1: Int = 0, rs2: Int = 0, rs3: Int = 0, imm: Int = 0): Long =
    ((op & 0xf).toLong << 28) | ((rd & 0xf).toLong << 24) | ((rs1 & 0xf).toLong << 20) |
      ((rs2 & 0xf).toLong << 16) | ((rs3 & 0xf).toLong << 12) | (imm & 0xfff).toLong

  describe("GPU with SFU") {

    it("computes e^x using SFU") {
      simulate(new ToyGpuTop(numSMs = 1, gmemLatency = 1)) { dut =>
        // 初始化
        dut.io.start.poke(false.B)
        dut.io.imemLoadEn.poke(false.B)
        dut.io.gmemExt.en.poke(false.B)
        dut.clock.step()

        // 写入测试数据到 gmem[0] = [0, 1, -1, 2] (Q16.16)
        dut.io.gmemExt.en.poke(true.B)
        dut.io.gmemExt.we.poke(true.B)
        dut.io.gmemExt.addr.poke(0.U)
        dut.io.gmemExt.wdata(0).poke(0.S) // 0.0
        dut.io.gmemExt.wdata(1).poke((1 << 16).S) // 1.0
        dut.io.gmemExt.wdata(2).poke((-1 << 16).S) // -1.0
        dut.io.gmemExt.wdata(3).poke((2 << 16).S) // 2.0
        dut.clock.step()
        dut.io.gmemExt.en.poke(false.B)
        dut.io.gmemExt.we.poke(false.B)
        dut.clock.step()

        // 程序：LD R0, [0] -> EXP R1, R0 -> ST [4], R1
        val program = Seq(
          enc(0x2, rd = 0, rs1 = 0, imm = 0), // LD R0, [R0+0]  (R0初始为0)
          enc(0x8, rd = 1, rs1 = 0), // EXP R1, R0
          enc(0x3, rs1 = 0, rs2 = 1, imm = 4), // ST [R0+4], R1  (存到地址4)
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
        dut.io.gmemExt.addr.poke(4.U) // 从地址4读取
        dut.clock.step()
        val result = Array.tabulate(W)(i => dut.io.gmemExt.rdata(i).peek().litValue.toInt)
        dut.io.gmemExt.en.poke(false.B)

        println(s"SFU result: ${result.mkString(", ")}")

        // 验证结果（大致范围）
        // e^0 = 1.0 = 65536
        // e^1 ≈ 2.718 = 178145
        // e^-1 ≈ 0.368 = 24109
        // e^2 ≈ 7.389 = 484380
        assert(math.abs(result(0) - 65536) < 5000, s"Lane 0: expected ~65536, got ${result(0)}")
        assert(math.abs(result(1) - 178145) < 10000, s"Lane 1: expected ~178145, got ${result(1)}")
        assert(math.abs(result(2) - 24109) < 5000, s"Lane 2: expected ~24109, got ${result(2)}")
        assert(math.abs(result(3) - 484380) < 20000, s"Lane 3: expected ~484380, got ${result(3)}")
      }
    }
  }
}
