package gpu

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec

class CudaCoreTest extends AnyFunSpec with ChiselSim {

  describe("CudaCore") {

    it("computes ADD") {
      simulate(new CudaCore) { dut =>
        dut.io.valid.poke(true.B)
        dut.io.op.poke(GpuOpcode.ADD)
        dut.io.rs1.poke(10.S)
        dut.io.rs2.poke(20.S)
        dut.io.rs3.poke(0.S)
        dut.clock.step()
        dut.io.rd.expect(30.S)
        assert(dut.io.done.peek().litToBoolean)
      }
    }

    it("computes MUL") {
      simulate(new CudaCore) { dut =>
        dut.io.valid.poke(true.B)
        dut.io.op.poke(GpuOpcode.MUL)
        dut.io.rs1.poke(7.S)
        dut.io.rs2.poke(-3.S)
        dut.io.rs3.poke(0.S)
        dut.clock.step()
        dut.io.rd.expect(-21.S)
      }
    }

    it("computes MAD: a*b+c") {
      simulate(new CudaCore) { dut =>
        dut.io.valid.poke(true.B)
        dut.io.op.poke(GpuOpcode.MAD)
        dut.io.rs1.poke(3.S)
        dut.io.rs2.poke(4.S)
        dut.io.rs3.poke(5.S)
        dut.clock.step()
        dut.io.rd.expect(17.S) // 3*4+5 = 17
      }
    }

    it("outputs zero when not valid") {
      simulate(new CudaCore) { dut =>
        dut.io.valid.poke(false.B)
        dut.io.op.poke(GpuOpcode.ADD)
        dut.io.rs1.poke(100.S)
        dut.io.rs2.poke(200.S)
        dut.io.rs3.poke(0.S)
        dut.clock.step()
        dut.io.rd.expect(0.S)
        assert(!dut.io.done.peek().litToBoolean)
      }
    }
  }
}
