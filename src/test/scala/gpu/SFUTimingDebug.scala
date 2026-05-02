package gpu

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

class SFUTimingDebug extends AnyFunSpec with Matchers {
  val W = GpuParams.WarpWidth
  val dw = GpuParams.DataWidth

  def enc(op: Int, rd: Int = 0, rs1: Int = 0, rs2: Int = 0, rs3: Int = 0, imm: Int = 0): Int = {
    (op << 28) | (rd << 24) | (rs1 << 20) | (rs2 << 16) | (rs3 << 12) | (imm & 0xFFF)
  }

  describe("SFU Timing Debug") {
    it("trace EXP execution") {
      simulate(new ToyGpuTop(numSMs = 1, gmemLatency = 1)) { dut =>
        // 初始化
        dut.io.start.poke(false.B)
        dut.io.imemLoadEn.poke(false.B)
        dut.io.gmemExt.en.poke(false.B)
        dut.clock.step()

        // 程序：EXP R1, R0 -> HALT
        val program = Seq(
          enc(0x8, rd = 1, rs1 = 0),   // EXP R1, R0
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
        while (!dut.io.allHalted.peek().litToBoolean && cycles < 10) {
          println(s"\nCycle $cycles:")
          println(s"  allHalted: ${dut.io.allHalted.peek().litToBoolean}")

          dut.clock.step()
          cycles += 1
        }

        println(s"\nHalted after $cycles cycles")

        // 尝试读取 R1 的值（通过执行 ST [0], R1）
        // 加载新程序
        val storeProgram = Seq(
          enc(0x3, rs1 = 0, rs2 = 1, imm = 0), // ST [R0+0], R1
          enc(0x1)                              // HALT
        )

        // 重置并加载新程序
        dut.reset.poke(true.B)
        dut.clock.step()
        dut.reset.poke(false.B)
        dut.clock.step()

        for ((instr, i) <- storeProgram.zipWithIndex) {
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
        cycles = 0
        while (!dut.io.allHalted.peek().litToBoolean && cycles < 10) {
          dut.clock.step()
          cycles += 1
        }

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
