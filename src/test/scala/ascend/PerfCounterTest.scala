package ascend

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec

class PerfCounterTest extends AnyFunSpec with ChiselSim {

  val N = AscendParams.ArraySize

  def encLoad(bufSel: Int, memAddr: Int): Int =
    (0x2 << 28) | ((bufSel & 0x3) << 26) | ((memAddr & 0xFFFF) << 4)
  def encStore(bufSel: Int, memAddr: Int): Int =
    (0x3 << 28) | ((bufSel & 0x3) << 26) | ((memAddr & 0xFFFF) << 4)
  def encMatmul: Int = 0x4 << 28
  def encNop: Int    = 0x0
  def encHalt: Int   = 0x1 << 28

  def loadProgram(dut: ToyAscendTop, instrs: Seq[Int]): Unit = {
    for ((instr, i) <- instrs.zipWithIndex) {
      dut.io.imemLoadEn.poke(true.B)
      dut.io.imemLoadAddr.poke(i.U)
      dut.io.imemLoadData.poke(instr.U)
      dut.clock.step()
    }
    dut.io.imemLoadEn.poke(false.B)
    dut.clock.step()
  }

  def writeUB(dut: ToyAscendTop, addr: Int, row: Array[Int]): Unit = {
    dut.io.ubExt.en.poke(true.B)
    dut.io.ubExt.we.poke(true.B)
    dut.io.ubExt.addr.poke(addr.U)
    for (j <- 0 until N) dut.io.ubExt.wdata(j).poke(row(j).S(32.W))
    dut.clock.step()
    dut.io.ubExt.en.poke(false.B)
    dut.io.ubExt.we.poke(false.B)
    dut.clock.step()
  }

  def initDut(dut: ToyAscendTop): Unit = {
    dut.io.start.poke(false.B)
    dut.io.imemLoadEn.poke(false.B)
    dut.io.ubExt.en.poke(false.B)
    dut.io.ubExt.we.poke(false.B)
  }

  def runToHalt(dut: ToyAscendTop, maxCycles: Int = 500): Int = {
    dut.io.start.poke(true.B)
    dut.clock.step()
    dut.io.start.poke(false.B)
    var cycles = 0
    while (!dut.io.halted.peek().litToBoolean && cycles < maxCycles) {
      dut.clock.step()
      cycles += 1
    }
    assert(dut.io.halted.peek().litToBoolean, s"Did not halt within $maxCycles cycles")
    dut.clock.step() // One more step to let perf counters settle
    cycles
  }

  def peekPerf(dut: ToyAscendTop): Map[String, Long] = Map(
    "totalCycles"       -> dut.io.perf.totalCycles.peek().litValue.toLong,
    "instrNop"          -> dut.io.perf.instrNop.peek().litValue.toLong,
    "instrHalt"         -> dut.io.perf.instrHalt.peek().litValue.toLong,
    "instrLoad"         -> dut.io.perf.instrLoad.peek().litValue.toLong,
    "instrStore"        -> dut.io.perf.instrStore.peek().litValue.toLong,
    "instrMatmul"       -> dut.io.perf.instrMatmul.peek().litValue.toLong,
    "instrVecadd"       -> dut.io.perf.instrVecadd.peek().litValue.toLong,
    "instrRelu"         -> dut.io.perf.instrRelu.peek().litValue.toLong,
    "cubeTotalCycles"   -> dut.io.perf.cubeTotalCycles.peek().litValue.toLong,
    "cubeComputeCycles" -> dut.io.perf.cubeComputeCycles.peek().litValue.toLong,
    "bubbleCycles"      -> dut.io.perf.bubbleCycles.peek().litValue.toLong,
    "ubReads"           -> dut.io.perf.ubReads.peek().litValue.toLong,
    "ubWrites"          -> dut.io.perf.ubWrites.peek().litValue.toLong,
    "vecaddCount"       -> dut.io.perf.vecaddCount.peek().litValue.toLong,
    "reluCount"         -> dut.io.perf.reluCount.peek().litValue.toLong
  )

  def printPerf(p: Map[String, Long]): Unit = {
    println("\n=== Performance Counters ===")
    println(f"  Total cycles:        ${p("totalCycles")}%d")
    println(f"  Instructions:        NOP=${p("instrNop")}%d HALT=${p("instrHalt")}%d LOAD=${p("instrLoad")}%d STORE=${p("instrStore")}%d MATMUL=${p("instrMatmul")}%d VECADD=${p("instrVecadd")}%d RELU=${p("instrRelu")}%d")
    println(f"  Cube total cycles:   ${p("cubeTotalCycles")}%d")
    println(f"  Cube compute cycles: ${p("cubeComputeCycles")}%d")
    if (p("cubeTotalCycles") > 0) {
      val util = p("cubeComputeCycles").toDouble / p("cubeTotalCycles").toDouble * 100
      println(f"  Cube utilization:    $util%.1f%%")
    }
    println(f"  Bubble cycles:       ${p("bubbleCycles")}%d")
    println(f"  UB reads/writes:     ${p("ubReads")}%d / ${p("ubWrites")}%d")
    println(f"  VECADD/RELU count:   ${p("vecaddCount")}%d / ${p("reluCount")}%d")
    println("============================\n")
  }

  describe("PerfCounters") {

    it("counts NOP + HALT correctly") {
      simulate(new ToyAscendTop) { dut =>
        initDut(dut)
        loadProgram(dut, Seq(encNop, encHalt))
        runToHalt(dut)

        val p = peekPerf(dut)
        printPerf(p)

        assert(p("instrNop") == 1, s"instrNop: expected 1, got ${p("instrNop")}")
        assert(p("instrHalt") == 1, s"instrHalt: expected 1, got ${p("instrHalt")}")
        assert(p("totalCycles") > 0, "totalCycles should be > 0")
        assert(p("cubeTotalCycles") == 0, "no MATMUL, cubeTotalCycles should be 0")
        assert(p("vecaddCount") == 0, "no VECADD")
        assert(p("reluCount") == 0, "no RELU")
      }
    }

    it("counts LOAD -> MATMUL -> STORE -> HALT correctly") {
      simulate(new ToyAscendTop) { dut =>
        initDut(dut)

        val a = Array(
          Array(1, 2, 3, 4), Array(5, 6, 7, 8),
          Array(2, 3, 1, 4), Array(7, 1, 5, 3)
        )
        val w = Array(
          Array(1, 0, 2, 1), Array(3, 1, 0, 2),
          Array(2, 4, 1, 3), Array(0, 2, 3, 1)
        )

        val program = Seq(
          encLoad(1, 0),  // LOAD L0_B
          encLoad(0, 4),  // LOAD L0_A
          encMatmul,
          encStore(2, 8), // STORE L0_C
          encHalt
        )
        loadProgram(dut, program)
        for (i <- 0 until N) writeUB(dut, i, a(i))
        for (i <- 0 until N) writeUB(dut, 4 + i, w(i))

        runToHalt(dut)

        val p = peekPerf(dut)
        printPerf(p)

        assert(p("instrLoad") == 2, s"instrLoad: expected 2, got ${p("instrLoad")}")
        assert(p("instrMatmul") == 1, s"instrMatmul: expected 1, got ${p("instrMatmul")}")
        assert(p("instrStore") == 1, s"instrStore: expected 1, got ${p("instrStore")}")
        assert(p("instrHalt") == 1, s"instrHalt: expected 1, got ${p("instrHalt")}")
        assert(p("cubeTotalCycles") > 0, "cubeTotalCycles should be > 0")
        assert(p("cubeComputeCycles") > 0, "cubeComputeCycles should be > 0")
        assert(p("cubeComputeCycles") <= p("cubeTotalCycles"),
          s"cubeCompute (${p("cubeComputeCycles")}) should be <= cubeTotal (${p("cubeTotalCycles")})")
        assert(p("ubReads") > 0, "ubReads should be > 0")
        assert(p("ubWrites") > 0, "ubWrites should be > 0")
        assert(p("totalCycles") > 0, "totalCycles should be > 0")
      }
    }
  }
}
