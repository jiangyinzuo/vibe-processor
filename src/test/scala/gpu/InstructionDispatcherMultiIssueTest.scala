package gpu

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec

class InstructionDispatcherMultiIssueTest extends AnyFunSpec with ChiselSim {

  def enc(op: Int, rd: Int = 0, rs1: Int = 0, rs2: Int = 0, rs3: Int = 0, imm: Int = 0): Long =
    ((op & 0xf).toLong << 28) | ((rd & 0xf).toLong << 24) | ((rs1 & 0xf).toLong << 20) |
      ((rs2 & 0xf).toLong << 16) | ((rs3 & 0xf).toLong << 12) | (imm & 0xfff).toLong

  describe("InstructionDispatcher multi-issue") {
    it("issues ALU and SFU work from different warps in the same sub-partition") {
      simulate(
        new InstructionDispatcher(
          numWarps = 4,
          warpWidth = 4,
          numCores = 8,
          numSchedulers = 2,
          gmemLatency = 1
        )
      ) { dut =>
        val warpWidth = 4
        val numCores = 8
        val numSchedulers = 2
        val localWarps = 2
        val rfPorts = warpWidth + 2 * numCores

        for (s <- 0 until numSchedulers) {
          for (w <- 0 until localWarps) {
            dut.io.selectedWarp(s)(w).valid.poke(false.B)
            dut.io.selectedWarp(s)(w).bits.poke(0.U)
          }
        }

        for (w <- 0 until 4) {
          dut.io.warpPC(w).poke(0.U)
          dut.io.warpState(w).poke(WarpState.Ready)
          dut.io.imemData(w).poke(enc(0x0).U)
        }
        dut.io.imemData(0).poke(enc(0x4, rd = 1, rs1 = 0, rs2 = 0).U)
        dut.io.imemData(1).poke(enc(0x8, rd = 2, rs1 = 0).U)

        for (i <- 0 until rfPorts) {
          dut.io.regRdData(i).rs1.poke(0.S)
          dut.io.regRdData(i).rs2.poke(0.S)
          dut.io.regRdData(i).rs3.poke(0.S)
        }

        for (i <- 0 until numCores) {
          dut.io.coreDone(i).poke(false.B)
          dut.io.coreRd(i).poke(0.S)
          dut.io.sfuDone(i).poke(false.B)
          dut.io.sfuRd(i).poke(0.S)
        }

        dut.io.selectedWarp(0)(0).valid.poke(true.B)
        dut.io.selectedWarp(0)(0).bits.poke(0.U)
        dut.io.selectedWarp(0)(1).valid.poke(true.B)
        dut.io.selectedWarp(0)(1).bits.poke(1.U)

        dut.clock.step(2)

        assert(dut.io.perf.aluIssue.peek().litToBoolean, "ALU issue event was not raised")
        assert(dut.io.perf.sfuIssue.peek().litToBoolean, "SFU issue event was not raised")
        assert(dut.io.perf.dualIssue.peek().litToBoolean, "dual-issue event was not raised")

        for (lane <- 0 until warpWidth) {
          assert(dut.io.coreValid(lane).peek().litToBoolean, s"ALU lane $lane did not issue")
          assert(dut.io.sfuValid(lane).peek().litToBoolean, s"SFU lane $lane did not issue")
          dut.io.coreWarpId(lane).expect(0.U)
          dut.io.sfuWarpId(lane).expect(1.U)
        }
      }
    }
  }
}
