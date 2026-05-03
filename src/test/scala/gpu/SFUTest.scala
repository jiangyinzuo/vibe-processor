package gpu

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec

/** SFU 单元测试：验证 e^x 功能 */
class SFUTest extends AnyFunSpec with ChiselSim {

  describe("SFU") {

    it("computes e^0 = 1") {
      simulate(new SFU) { dut =>
        dut.io.valid.poke(true.B)
        dut.io.op.poke(GpuOpcode.EXP)
        dut.io.x.poke(0.S) // 0 in Q16.16
        dut.clock.step(3) // SFU has 3-cycle pipeline latency
        val result = dut.io.result.peek().litValue.toInt
        println(s"e^0 result: $result")
        // e^0 = 1.0 = 65536 in Q16.16
        assert(math.abs(result - 65536) < 1000, s"Expected ~65536, got $result")
        assert(dut.io.done.peek().litToBoolean)
      }
    }

    it("computes e^1 ≈ 2.718") {
      simulate(new SFU) { dut =>
        dut.io.valid.poke(true.B)
        dut.io.op.poke(GpuOpcode.EXP)
        dut.io.x.poke((1 << 16).S) // 1.0 in Q16.16
        dut.clock.step(3)
        val result = dut.io.result.peek().litValue.toInt
        println(s"e^1 result: $result")
        // e^1 ≈ 2.718 = 178145 in Q16.16
        assert(math.abs(result - 178145) < 5000, s"Expected ~178145, got $result")
      }
    }

    it("computes e^-1 ≈ 0.368") {
      simulate(new SFU) { dut =>
        dut.io.valid.poke(true.B)
        dut.io.op.poke(GpuOpcode.EXP)
        dut.io.x.poke((-1 << 16).S) // -1.0 in Q16.16
        dut.clock.step(3)
        val result = dut.io.result.peek().litValue.toInt
        println(s"e^-1 result: $result")
        // e^-1 ≈ 0.368 = 24109 in Q16.16
        assert(math.abs(result - 24109) < 2000, s"Expected ~24109, got $result")
      }
    }

    it("outputs zero when not valid") {
      simulate(new SFU) { dut =>
        dut.io.valid.poke(false.B)
        dut.io.op.poke(GpuOpcode.EXP)
        dut.io.x.poke(0.S)
        dut.clock.step(3)
        dut.io.result.expect(0.S)
        assert(!dut.io.done.peek().litToBoolean)
      }
    }
  }
}
