package ascend

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec

class ControlCpuTest extends AnyFunSpec with ChiselSim {
  describe("ControlCpu") {
    it("reuses the SPMD scheduler to launch logical blocks onto physical cores") {
      simulate(new ControlCpu(numCores = 2)) { dut =>
        dut.io.start.poke(false.B)
        dut.io.blockDim.poke(5.U)
        dut.io.coreHalted.foreach(_.poke(false.B))
        dut.clock.step()

        dut.io.start.poke(true.B)
        dut.io.coreLaunch(0).valid.expect(true.B)
        dut.io.coreLaunch(0).bits.expect(0.U)
        dut.io.coreLaunch(1).valid.expect(true.B)
        dut.io.coreLaunch(1).bits.expect(1.U)
        dut.io.halted.expect(false.B)
        dut.clock.step()
        dut.io.start.poke(false.B)

        dut.io.coreActive(0).expect(true.B)
        dut.io.coreActive(1).expect(true.B)

        dut.io.coreHalted(0).poke(true.B)
        dut.clock.step()
        dut.io.coreHalted(0).poke(false.B)
        dut.io.coreLaunch(0).valid.expect(true.B)
        dut.io.coreLaunch(0).bits.expect(2.U)

        dut.clock.step()
        dut.io.coreHalted(1).poke(true.B)
        dut.clock.step()
        dut.io.coreHalted(1).poke(false.B)
        dut.io.coreLaunch(1).valid.expect(true.B)
        dut.io.coreLaunch(1).bits.expect(3.U)

        dut.clock.step()
        dut.io.coreHalted(0).poke(true.B)
        dut.clock.step()
        dut.io.coreHalted(0).poke(false.B)
        dut.io.coreLaunch(0).valid.expect(true.B)
        dut.io.coreLaunch(0).bits.expect(4.U)

        dut.clock.step()
        dut.io.coreHalted(1).poke(true.B)
        dut.clock.step()
        dut.io.coreHalted(1).poke(false.B)
        dut.io.halted.expect(false.B)

        dut.io.coreHalted(0).poke(true.B)
        dut.clock.step()
        dut.io.coreHalted(0).poke(false.B)
        dut.clock.step()
        dut.io.halted.expect(true.B)
        dut.io.dbgCompletedBlocks.expect(5.U)
      }
    }
  }
}

class AiCpuTest extends AnyFunSpec with ChiselSim {
  describe("AiCpu") {
    it("runs simple device-side L2 row tasks") {
      simulate(new AiCpu(n = 2, aw = 32, addrW = 8)) { dut =>
        val mem = scala.collection.mutable.Map[Int, Array[Int]](
          0 -> Array(1, 2),
          1 -> Array(3, 4),
          2 -> Array(5, 6)
        ).withDefaultValue(Array(0, 0))

        def driveReadData(): Unit = {
          val addr = dut.io.l2Addr.peek().litValue.toInt
          val row = mem(addr)
          for (i <- 0 until 2) dut.io.l2Rdata(i).poke(row(i).S)
        }

        def captureWrite(): Unit = {
          if (dut.io.l2En.peek().litToBoolean && dut.io.l2We.peek().litToBoolean) {
            val addr = dut.io.l2Addr.peek().litValue.toInt
            mem(addr) = Array.tabulate(2)(i => dut.io.l2Wdata(i).peek().litValue.toInt)
          }
        }

        def stepEngine(maxCycles: Int = 32): Unit = {
          var cycles = 0
          while (!dut.io.done.peek().litToBoolean && cycles < maxCycles) {
            if (dut.io.l2En.peek().litToBoolean && !dut.io.l2We.peek().litToBoolean) {
              driveReadData()
            }
            captureWrite()
            dut.clock.step()
            cycles += 1
          }
          assert(dut.io.done.peek().litToBoolean, s"AI CPU task did not finish within $maxCycles cycles")
          dut.clock.step()
        }

        dut.io.cmd.valid.poke(false.B)
        dut.io.cmd.bits.op.poke(AiCpuOp.FILL)
        dut.io.cmd.bits.src.poke(0.U)
        dut.io.cmd.bits.dst.poke(10.U)
        dut.io.cmd.bits.rows.poke(2.U)
        dut.io.cmd.bits.imm.poke(7.S)
        dut.clock.step()

        dut.io.cmd.valid.poke(true.B)
        dut.clock.step()
        dut.io.cmd.valid.poke(false.B)
        stepEngine()
        assert(mem(10).sameElements(Array(7, 7)))
        assert(mem(11).sameElements(Array(7, 7)))

        dut.io.cmd.bits.op.poke(AiCpuOp.COPY)
        dut.io.cmd.bits.src.poke(10.U)
        dut.io.cmd.bits.dst.poke(12.U)
        dut.io.cmd.bits.rows.poke(2.U)
        dut.io.cmd.bits.imm.poke(0.S)
        dut.io.cmd.valid.poke(true.B)
        dut.clock.step()
        dut.io.cmd.valid.poke(false.B)
        stepEngine()
        assert(mem(12).sameElements(Array(7, 7)))
        assert(mem(13).sameElements(Array(7, 7)))

        dut.io.cmd.bits.op.poke(AiCpuOp.ADD_IMM)
        dut.io.cmd.bits.src.poke(0.U)
        dut.io.cmd.bits.dst.poke(20.U)
        dut.io.cmd.bits.rows.poke(3.U)
        dut.io.cmd.bits.imm.poke(10.S)
        dut.io.cmd.valid.poke(true.B)
        dut.clock.step()
        dut.io.cmd.valid.poke(false.B)
        stepEngine()

        assert(mem(20).sameElements(Array(11, 12)))
        assert(mem(21).sameElements(Array(13, 14)))
        assert(mem(22).sameElements(Array(15, 16)))
      }
    }
  }
}
