package gpu

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec

/** SFU 调试测试 */
class SFUDebugTest extends AnyFunSpec with ChiselSim {

  val W  = GpuParams.WarpWidth
  val DW = GpuParams.DataWidth

  def enc(op: Int, rd: Int = 0, rs1: Int = 0, rs2: Int = 0, rs3: Int = 0, imm: Int = 0): Long =
    ((op & 0xF).toLong << 28) | ((rd & 0xF).toLong << 24) | ((rs1 & 0xF).toLong << 20) |
    ((rs2 & 0xF).toLong << 16) | ((rs3 & 0xF).toLong << 12) | (imm & 0xFFF).toLong

  describe("SFU Debug") {

    it("simple EXP test without memory") {
      simulate(new ToyGpuTop(numSMs = 1, gmemLatency = 1)) { dut =>
        // 初始化
        dut.io.start.poke(false.B)
        dut.io.imemLoadEn.poke(false.B)
        dut.io.gmemExt.en.poke(false.B)
        dut.clock.step()

        // 简单程序：EXP R1, R0 (R0初始为0) -> HALT
        // 期望：R1 = e^0 = 1.0 = 65536
        val program = Seq(
          enc(0x8, rd = 1, rs1 = 0),  // EXP R1, R0
          enc(0x1)                     // HALT
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

        // 运行到 halt。RF/EX/WB 和 SFU 都已流水化，EXP 程序需要更多周期。
        val maxCycles = 80
        var cycles = 0
        while (!dut.io.allHalted.peek().litToBoolean && cycles < maxCycles) {
          dut.clock.step()
          cycles += 1
        }

        println(s"Halted after $cycles cycles")
        assert(dut.io.allHalted.peek().litToBoolean, s"Did not halt within $maxCycles cycles")

        // 注意：我们无法直接读取寄存器，所以这个测试只能验证程序能正常执行
        println("EXP instruction executed successfully")
      }
    }

    it("EXP with STORE") {
      simulate(new ToyGpuTop(numSMs = 1, gmemLatency = 1)) { dut =>
        // 初始化
        dut.io.start.poke(false.B)
        dut.io.imemLoadEn.poke(false.B)
        dut.io.gmemExt.en.poke(false.B)
        dut.clock.step()

        // 程序：EXP R1, R0 -> ST [0], R1 -> HALT
        val program = Seq(
          enc(0x8, rd = 1, rs1 = 0),   // EXP R1, R0 (R0=0, 所以 R1 = e^0 = 65536)
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

        // 运行到 halt。RF/EX/WB 和 SFU 都已流水化，EXP+STORE 程序需要更多周期。
        val maxCycles = 80
        var cycles = 0
        while (!dut.io.allHalted.peek().litToBoolean && cycles < maxCycles) {
          dut.clock.step()
          cycles += 1
        }

        println(s"Halted after $cycles cycles")
        assert(dut.io.allHalted.peek().litToBoolean, s"Did not halt within $maxCycles cycles")

        // 读取结果
        dut.io.gmemExt.en.poke(true.B)
        dut.io.gmemExt.we.poke(false.B)
        dut.io.gmemExt.addr.poke(0.U)
        dut.clock.step()
        val result = Array.tabulate(W)(i => dut.io.gmemExt.rdata(i).peek().litValue.toInt)
        dut.io.gmemExt.en.poke(false.B)

        println(s"Result: ${result.mkString(", ")}")

        // 验证结果（e^0 = 1.0 = 65536）
        assert(math.abs(result(0) - 65536) < 5000, s"Lane 0: expected ~65536, got ${result(0)}")
      }
    }
  }
}
