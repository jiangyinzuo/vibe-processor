package ascend

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec

class PETest extends AnyFunSpec with ChiselSim {

  describe("PE") {

    it("computes MAC: psumOut = psumIn + weight * dataIn") {
      simulate(new PE) { dut =>
        // Load weight = 3
        dut.io.weightLoad.poke(true.B)
        dut.io.weightIn.poke(3.S(8.W))
        dut.io.dataIn.poke(0.S(8.W))
        dut.io.psumIn.poke(0.S(32.W))
        dut.clock.step()

        // Weight loaded, now feed data
        dut.io.weightLoad.poke(false.B)
        dut.io.dataIn.poke(5.S(8.W))
        dut.io.psumIn.poke(10.S(32.W))
        dut.clock.step()
        // After this step, psumOut register captures 10 + 3*5 = 25

        // Check result (psumOut is registered, so available now)
        dut.io.psumOut.expect(25.S(32.W))
        dut.io.dataOut.expect(5.S(8.W))
      }
    }

    it("passes data through with 1-cycle delay") {
      simulate(new PE) { dut =>
        dut.io.weightLoad.poke(false.B)
        dut.io.weightIn.poke(0.S(8.W))
        dut.io.psumIn.poke(0.S(32.W))

        // After reset, dataOut = 0
        // poke dataIn=10, step → dataOut = 10 (RegNext captures 10)
        // poke dataIn=20, step → dataOut = 20
        // So RegNext output is available immediately after the step that samples it.
        val values = Seq(10, 20, 30, 40)
        for (v <- values) {
          dut.io.dataIn.poke(v.S(8.W))
          dut.clock.step()
          dut.io.dataOut.expect(v.S(8.W))
        }
      }
    }

    it("accumulates over multiple cycles with chained psum") {
      simulate(new PE) { dut =>
        dut.io.dataIn.poke(0.S(8.W))
        dut.io.psumIn.poke(0.S(32.W))

        // Load weight = 2
        dut.io.weightLoad.poke(true.B)
        dut.io.weightIn.poke(2.S(8.W))
        dut.clock.step()
        dut.io.weightLoad.poke(false.B)

        // Feed data=1, psum=0 → psumOut will be 0+2*1=2
        dut.io.dataIn.poke(1.S(8.W))
        dut.io.psumIn.poke(0.S(32.W))
        dut.clock.step()
        dut.io.psumOut.expect(2.S(32.W))

        // Feed data=2, psum=2 → psumOut will be 2+2*2=6
        dut.io.dataIn.poke(2.S(8.W))
        dut.io.psumIn.poke(2.S(32.W))
        dut.clock.step()
        dut.io.psumOut.expect(6.S(32.W))

        // Feed data=3, psum=6 → psumOut will be 6+2*3=12
        dut.io.dataIn.poke(3.S(8.W))
        dut.io.psumIn.poke(6.S(32.W))
        dut.clock.step()
        dut.io.psumOut.expect(12.S(32.W))
      }
    }
  }
}
