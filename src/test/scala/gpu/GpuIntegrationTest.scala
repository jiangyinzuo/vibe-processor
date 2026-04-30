package gpu

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec

class GpuIntegrationTest extends AnyFunSpec with ChiselSim {

  val W  = GpuParams.WarpWidth
  val DW = GpuParams.DataWidth

  def enc(op: Int, rd: Int = 0, rs1: Int = 0, rs2: Int = 0, rs3: Int = 0, imm: Int = 0): Long =
    ((op & 0xF).toLong << 28) | ((rd & 0xF).toLong << 24) | ((rs1 & 0xF).toLong << 20) |
    ((rs2 & 0xF).toLong << 16) | ((rs3 & 0xF).toLong << 12) | (imm & 0xFFF).toLong

  def loadProgram(dut: ToyGpuTop, instrs: Seq[Long]): Unit = {
    for ((instr, i) <- instrs.zipWithIndex) {
      dut.io.imemLoadEn.poke(true.B)
      dut.io.imemLoadAddr.poke(i.U)
      dut.io.imemLoadData.poke(instr.U)
      dut.clock.step()
    }
    dut.io.imemLoadEn.poke(false.B)
    dut.clock.step()
  }

  def writeGmem(dut: ToyGpuTop, addr: Int, values: Array[Int]): Unit = {
    dut.io.gmemExt.en.poke(true.B)
    dut.io.gmemExt.we.poke(true.B)
    dut.io.gmemExt.addr.poke(addr.U)
    for (i <- 0 until W) dut.io.gmemExt.wdata(i).poke(values(i).S(DW.W))
    dut.clock.step()
    dut.io.gmemExt.en.poke(false.B)
    dut.io.gmemExt.we.poke(false.B)
    dut.clock.step()
  }

  def readGmem(dut: ToyGpuTop, addr: Int): Array[Int] = {
    dut.io.gmemExt.en.poke(true.B)
    dut.io.gmemExt.we.poke(false.B)
    dut.io.gmemExt.addr.poke(addr.U)
    dut.clock.step()
    dut.io.gmemExt.en.poke(false.B)
    val result = Array.tabulate(W)(i => dut.io.gmemExt.rdata(i).peek().litValue.toInt)
    dut.clock.step()
    result
  }

  def initDut(dut: ToyGpuTop): Unit = {
    dut.io.start.poke(false.B)
    dut.io.imemLoadEn.poke(false.B)
    dut.io.gmemExt.en.poke(false.B)
    dut.io.gmemExt.we.poke(false.B)
  }

  def runToHalt(dut: ToyGpuTop, maxCycles: Int = 1000): Int = {
    dut.io.start.poke(true.B)
    dut.clock.step()
    dut.io.start.poke(false.B)
    var cycles = 0
    while (!dut.io.allHalted.peek().litToBoolean && cycles < maxCycles) {
      dut.clock.step()
      cycles += 1
    }
    assert(dut.io.allHalted.peek().litToBoolean, s"Did not halt within $maxCycles cycles")
    dut.clock.step()
    cycles
  }

  // Simple ADD program: R2 = R0 + R1, then store
  val addProgram = Seq(
    enc(0x2, rd = 0, rs1 = 15, imm = 0),  // LD R0, [R15+0]
    enc(0x2, rd = 1, rs1 = 15, imm = 1),  // LD R1, [R15+1]
    enc(0x4, rd = 2, rs1 = 0, rs2 = 1),   // ADD R2, R0, R1
    enc(0x3, rs1 = 15, rs2 = 2, imm = 2), // ST [R15+2], R2
    enc(0x1)                                // HALT
  )

  describe("ToyGpuTop Multi-SM") {

    it("runs NOP + HALT on all SMs") {
      simulate(new ToyGpuTop(gmemLatency = 1)) { dut =>
        initDut(dut)
        loadProgram(dut, Seq(enc(0x0), enc(0x1)))
        runToHalt(dut)
      }
    }

    it("4 SMs run vector ADD in parallel (latency=1)") {
      simulate(new ToyGpuTop(gmemLatency = 1)) { dut =>
        initDut(dut)

        // All 4 SMs × 4 warps all read gmem[0] and gmem[1], write to gmem[2]
        // (same data, just verifying multi-SM works)
        writeGmem(dut, 0, Array(10, 20, 30, 40))
        writeGmem(dut, 1, Array(1, 2, 3, 4))
        loadProgram(dut, addProgram)

        val cycles = runToHalt(dut)
        val result = readGmem(dut, 2)

        println(s"4-SM VADD (latency=1): $cycles cycles, result=${result.mkString(",")}")
        assert(result.toSeq == Seq(11, 22, 33, 44))

        // Print per-SM perf
        for (s <- 0 until GpuParams.NumSMs) {
          val total = dut.io.perf(s).totalCycles.peek().litValue.toLong
          val active = dut.io.perf(s).activeWarpCycles.peek().litValue.toLong
          println(s"  SM $s: total=$total, active=$active")
        }
      }
    }

    it("4 SMs with latency=10 (off-chip DRAM)") {
      simulate(new ToyGpuTop(gmemLatency = 10)) { dut =>
        initDut(dut)
        writeGmem(dut, 0, Array(10, 20, 30, 40))
        writeGmem(dut, 1, Array(1, 2, 3, 4))
        loadProgram(dut, addProgram)

        val cycles = runToHalt(dut)
        val result = readGmem(dut, 2)

        println(s"4-SM VADD (latency=10): $cycles cycles")
        assert(result.toSeq == Seq(11, 22, 33, 44))

        for (s <- 0 until GpuParams.NumSMs) {
          val total = dut.io.perf(s).totalCycles.peek().litValue.toLong
          println(s"  SM $s: total=$total")
        }
      }
    }
  }
}
