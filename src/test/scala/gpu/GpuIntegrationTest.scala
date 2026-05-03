package gpu

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec

class GpuIntegrationTest extends AnyFunSpec with ChiselSim {

  val W = GpuParams.WarpWidth
  val DW = GpuParams.DataWidth

  def enc(op: Int, rd: Int = 0, rs1: Int = 0, rs2: Int = 0, rs3: Int = 0, imm: Int = 0): Long =
    ((op & 0xf).toLong << 28) | ((rd & 0xf).toLong << 24) | ((rs1 & 0xf).toLong << 20) |
      ((rs2 & 0xf).toLong << 16) | ((rs3 & 0xf).toLong << 12) | (imm & 0xfff).toLong

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

  def printSmPerf(dut: ToyGpuTop, smId: Int): Unit = {
    val perf = dut.io.perf(smId)
    val total = perf.totalCycles.peek().litValue.toLong
    val live = perf.activeWarpCycles.peek().litValue.toLong
    val eligible = perf.eligibleWarpCycles.peek().litValue.toLong
    val stalled = perf.stalledWarpCycles.peek().litValue.toLong
    val noEligible = perf.noEligibleCycles.peek().litValue.toLong
    val aluIssue = perf.aluIssueCycles.peek().litValue.toLong
    val memIssue = perf.memIssueCycles.peek().litValue.toLong
    val dualIssue = perf.dualIssueCycles.peek().litValue.toLong
    println(
      s"  SM $smId: total=$total, liveWarp=$live, eligible=$eligible, stalled=$stalled, " +
        s"noEligible=$noEligible, aluIssue=$aluIssue, memIssue=$memIssue, dualIssue=$dualIssue"
    )
  }

  def runToHalt(dut: ToyGpuTop, maxCycles: Int = 600): Int = {
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
    enc(0x2, rd = 0, rs1 = 15, imm = 0), // LD R0, [R15+0]
    enc(0x2, rd = 1, rs1 = 15, imm = 1), // LD R1, [R15+1]
    enc(0x4, rd = 2, rs1 = 0, rs2 = 1), // ADD R2, R0, R1
    enc(0x3, rs1 = 15, rs2 = 2, imm = 2), // ST [R15+2], R2
    enc(0x1) // HALT
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

        for (s <- 0 until GpuParams.NumSMs) {
          printSmPerf(dut, s)
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
          printSmPerf(dut, s)
        }
      }
    }

    it("runs multiple CTAs per SM and exposes thread/block IDs") {
      simulate(new ToyGpuTop(numSMs = 1, numCTAs = 4, gmemLatency = 1)) { dut =>
        initDut(dut)
        writeGmem(dut, 0, Array.fill(W)(GpuParams.ThreadsPerCTA))

        loadProgram(
          dut,
          Seq(
            enc(0x2, rd = 0, rs1 = GpuSpecialReg.Zero, imm = 0), // R0 = blockDim.x
            enc(
              0x5,
              rd = 1,
              rs1 = GpuSpecialReg.BlockIdxX,
              rs2 = 0
            ), // R1 = blockIdx.x * blockDim.x
            enc(0x4, rd = 2, rs1 = 1, rs2 = GpuSpecialReg.ThreadIdxX), // R2 = global thread id
            enc(0x3, rs1 = 2, rs2 = GpuSpecialReg.ThreadIdxX, imm = 16), // store threadIdx.x
            enc(0x3, rs1 = 2, rs2 = GpuSpecialReg.BlockIdxX, imm = 64), // store blockIdx.x
            enc(0x1)
          )
        )

        val cycles = runToHalt(dut, maxCycles = 400)
        println(s"1-SM 4-CTA ID test: $cycles cycles")

        dut.io.perf(0).ctaLaunches.expect(4.U)
        dut.io.perf(0).ctaCompletions.expect(4.U)

        for (cta <- 0 until 4) {
          val base = cta * GpuParams.ThreadsPerCTA
          assert(readGmem(dut, 16 + base).toSeq == Seq(0, 1, 2, 3))
          assert(readGmem(dut, 16 + base + W).toSeq == Seq(4, 5, 6, 7))
          assert(readGmem(dut, 64 + base).toSeq == Seq.fill(W)(cta))
          assert(readGmem(dut, 64 + base + W).toSeq == Seq.fill(W)(cta))
        }
      }
    }
  }
}
