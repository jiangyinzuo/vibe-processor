package common

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec

class LatencyMemTest extends AnyFunSpec with ChiselSim {

  describe("LatencyMem") {

    it("latency=1: single-cycle read/write") {
      simulate(new LatencyMem(SInt(32.W), depth = 64, latency = 1, addrW = 8)) { dut =>
        // Write value 42 to addr 5
        dut.io.req.valid.poke(true.B)
        dut.io.req.we.poke(true.B)
        dut.io.req.addr.poke(5.U)
        dut.io.req.wdata.poke(42.S)
        dut.clock.step()

        // Read addr 5
        dut.io.req.we.poke(false.B)
        dut.io.req.addr.poke(5.U)
        dut.clock.step()

        // Response available after 1 cycle
        assert(dut.io.resp.valid.peek().litToBoolean, "resp should be valid")
        dut.io.resp.rdata.expect(42.S)
      }
    }

    it("latency=1: always ready") {
      simulate(new LatencyMem(SInt(32.W), depth = 64, latency = 1, addrW = 8)) { dut =>
        dut.io.req.valid.poke(false.B)
        dut.io.req.we.poke(false.B)
        assert(dut.io.req.ready.peek().litToBoolean, "should always be ready at latency=1")
      }
    }

    it("latency=5: read takes 5 cycles") {
      simulate(new LatencyMem(SInt(32.W), depth = 64, latency = 5, addrW = 8)) { dut =>
        // Write
        dut.io.req.valid.poke(true.B)
        dut.io.req.we.poke(true.B)
        dut.io.req.addr.poke(3.U)
        dut.io.req.wdata.poke(99.S)
        dut.clock.step()

        // Start read
        dut.io.req.we.poke(false.B)
        dut.io.req.addr.poke(3.U)
        dut.io.req.valid.poke(true.B)
        dut.clock.step()
        dut.io.req.valid.poke(false.B)

        // Not ready during processing
        assert(!dut.io.req.ready.peek().litToBoolean, "should be busy")

        // Wait for latency-1 more cycles (already spent 1 in sBusy transition)
        for (c <- 1 until 5) {
          assert(!dut.io.resp.valid.peek().litToBoolean, s"cycle $c: resp should not be valid yet")
          dut.clock.step()
        }

        // Now response should be valid
        assert(dut.io.resp.valid.peek().litToBoolean, "resp should be valid after 5 cycles")
        dut.io.resp.rdata.expect(99.S)
      }
    }

    it("latency=10: not ready while busy, ready again after completion") {
      simulate(new LatencyMem(SInt(32.W), depth = 64, latency = 10, addrW = 8)) { dut =>
        // Write
        dut.io.req.valid.poke(true.B)
        dut.io.req.we.poke(true.B)
        dut.io.req.addr.poke(0.U)
        dut.io.req.wdata.poke(77.S)
        dut.clock.step()

        // Read
        dut.io.req.we.poke(false.B)
        dut.io.req.valid.poke(true.B)
        dut.clock.step()
        dut.io.req.valid.poke(false.B)

        // Busy for 10 cycles
        for (_ <- 0 until 9) {
          assert(!dut.io.req.ready.peek().litToBoolean, "should be busy")
          dut.clock.step()
        }

        // Response valid
        assert(dut.io.resp.valid.peek().litToBoolean)
        dut.io.resp.rdata.expect(77.S)

        // Ready again
        dut.clock.step()
        assert(dut.io.req.ready.peek().litToBoolean, "should be ready again")
      }
    }
  }
}
