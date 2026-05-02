package gpu

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec

class SFUTimingDebug extends AnyFunSpec with ChiselSim {
  val W = GpuParams.WarpWidth
  val dw = GpuParams.DataWidth

  def enc(op: Int, rd: Int = 0, rs1: Int = 0, rs2: Int = 0, rs3: Int = 0, imm: Int = 0): Long =
    ((op & 0xF).toLong << 28) | ((rd & 0xF).toLong << 24) | ((rs1 & 0xF).toLong << 20) |
      ((rs2 & 0xF).toLong << 16) | ((rs3 & 0xF).toLong << 12) | (imm & 0xFFF).toLong

  describe("SFU Timing Debug") {
    it("trace EXP execution") {
      simulate(new ToyGpuTop(numSMs = 1, gmemLatency = 1)) { dut =>
        // 初始化
        dut.io.start.poke(false.B)
        dut.io.imemLoadEn.poke(false.B)
        dut.io.gmemExt.en.poke(false.B)
        dut.clock.step()

        // 程序：EXP R1, R0 -> ST [0], R1 -> HALT
        val program = Seq(
          enc(0x8, rd = 1, rs1 = 0),   // EXP R1, R0
          enc(0x3, rs1 = 0, rs2 = 1, imm = 0), // ST [R0+0], R1
          enc(0x1)                      // HALT
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

        // 运行并跟踪
        println("\n=== Cycle-by-cycle trace ===")
        var cycles = 0
        while (!dut.io.allHalted.peek().litToBoolean && cycles < 20) {
          println(s"\nCycle $cycles:")
          println(s"  allHalted: ${dut.io.allHalted.peek().litToBoolean}")

          dut.clock.step()
          cycles += 1
        }

        println(s"\nHalted after $cycles cycles")
        assert(dut.io.allHalted.peek().litToBoolean, s"Did not halt within 20 cycles")

        // 读取结果
        dut.io.gmemExt.en.poke(true.B)
        dut.io.gmemExt.we.poke(false.B)
        dut.io.gmemExt.addr.poke(0.U)
        dut.clock.step()
        val result = Array.tabulate(W)(i => dut.io.gmemExt.rdata(i).peek().litValue.toInt)
        dut.io.gmemExt.en.poke(false.B)

        println(s"\nResult after STORE: ${result.mkString(", ")}")
        println(s"Expected: ~65536 (e^0 = 1.0 in Q16.16)")

        // 检查结果
        assert(result(0) != 0, s"Lane 0: R1 was not written by EXP, got ${result(0)}")
      }
    }
  }
}
