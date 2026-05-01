package ascend

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec

class VectorUnitTest extends AnyFunSpec with ChiselSim {

  val N = AscendParams.ArraySize

  describe("VectorUnit") {

    it("computes VECADD") {
      simulate(new VectorUnit) { dut =>
        val a = Array.tabulate(N)(i => 10 + i * 5)
        val b = Array.tabulate(N)(i => 3 - i * 2)

        for (i <- 0 until N) {
          dut.io.src1(i).poke(a(i).S(32.W))
          dut.io.src2(i).poke(b(i).S(32.W))
        }
        dut.io.op.poke(0.U)
        dut.io.start.poke(true.B)
        dut.clock.step()
        dut.io.start.poke(false.B)

        assert(dut.io.done.peek().litToBoolean)
        for (i <- 0 until N) {
          dut.io.dst(i).expect((a(i) + b(i)).S(32.W))
        }
      }
    }

    it("computes RELU with mixed values") {
      simulate(new VectorUnit) { dut =>
        val a = Array.tabulate(N)(i => 10 - i * 5)

        for (i <- 0 until N) {
          dut.io.src1(i).poke(a(i).S(32.W))
          dut.io.src2(i).poke(0.S(32.W))
        }
        dut.io.op.poke(1.U)
        dut.io.start.poke(true.B)
        dut.clock.step()
        dut.io.start.poke(false.B)

        assert(dut.io.done.peek().litToBoolean)
        val expected = a.map(x => if (x > 0) x else 0)
        for (i <- 0 until N) {
          dut.io.dst(i).expect(expected(i).S(32.W))
        }
      }
    }

    it("computes RELU with all negative values") {
      simulate(new VectorUnit) { dut =>
        val a = Array.tabulate(N)(i => -1 - i * 10)

        for (i <- 0 until N) {
          dut.io.src1(i).poke(a(i).S(32.W))
          dut.io.src2(i).poke(0.S(32.W))
        }
        dut.io.op.poke(1.U)
        dut.io.start.poke(true.B)
        dut.clock.step()
        dut.io.start.poke(false.B)

        assert(dut.io.done.peek().litToBoolean)
        for (i <- 0 until N) {
          dut.io.dst(i).expect(0.S(32.W))
        }
      }
    }
  }
}
