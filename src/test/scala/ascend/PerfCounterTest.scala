package ascend

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec

class PerfCounterTest extends AnyFunSpec with ChiselSim {

  val N  = AscendParams.ArraySize
  val AW = AscendParams.AccWidth

  def encDmaLoad(ubBase: Int, l2Base: Int): Long =
    (0x8L << 28) | ((ubBase & 0xFF).toLong << 20) | ((l2Base & 0xFFFF).toLong << 4)
  def encDmaStore(ubBase: Int, l2Base: Int): Long =
    (0x9L << 28) | ((ubBase & 0xFF).toLong << 20) | ((l2Base & 0xFFFF).toLong << 4)
  def encLoad(bufSel: Int, memAddr: Int): Long =
    (0x2L << 28) | ((bufSel & 0x3).toLong << 26) | ((memAddr & 0xFFFF).toLong << 4)
  def encStore(bufSel: Int, memAddr: Int): Long =
    (0x3L << 28) | ((bufSel & 0x3).toLong << 26) | ((memAddr & 0xFFFF).toLong << 4)
  def encMatmul: Long = 0x4L << 28
  def encNop: Long    = 0x0L
  def encHalt: Long   = 0x1L << 28

  def loadProgram(dut: ToyAscendTop, instrs: Seq[Long]): Unit = {
    for ((instr, i) <- instrs.zipWithIndex) {
      dut.io.imemLoadEn.poke(true.B)
      dut.io.imemLoadAddr.poke(i.U)
      dut.io.imemLoadData.poke(instr.U)
      dut.clock.step()
    }
    dut.io.imemLoadEn.poke(false.B)
    dut.clock.step()
  }

  def writeL2(dut: ToyAscendTop, addr: Int, row: Array[Int]): Unit = {
    dut.io.l2Ext.en.poke(true.B)
    dut.io.l2Ext.we.poke(true.B)
    dut.io.l2Ext.addr.poke(addr.U)
    for (j <- 0 until N) dut.io.l2Ext.wdata(j).poke(row(j).S(AW.W))
    dut.clock.step()
    dut.io.l2Ext.en.poke(false.B)
    dut.io.l2Ext.we.poke(false.B)
    dut.clock.step()
  }

  def initDut(dut: ToyAscendTop): Unit = {
    dut.io.start.poke(false.B)
    dut.io.imemLoadEn.poke(false.B)
    dut.io.l2Ext.en.poke(false.B)
    dut.io.l2Ext.we.poke(false.B)
    dut.io.hbmExt.en.poke(false.B)
    dut.io.hbmExt.we.poke(false.B)
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
    dut.clock.step()
    cycles
  }

  // Read perf counters from core 0
  def peekPerf(dut: ToyAscendTop, core: Int = 0): Map[String, Long] = {
    val p = dut.io.perf(core)
    Map(
      "totalCycles"       -> p.totalCycles.peek().litValue.toLong,
      "instrNop"          -> p.instrNop.peek().litValue.toLong,
      "instrHalt"         -> p.instrHalt.peek().litValue.toLong,
      "instrLoad"         -> p.instrLoad.peek().litValue.toLong,
      "instrStore"        -> p.instrStore.peek().litValue.toLong,
      "instrMatmul"       -> p.instrMatmul.peek().litValue.toLong,
      "cubeTotalCycles"   -> p.cubeTotalCycles.peek().litValue.toLong,
      "cubeComputeCycles" -> p.cubeComputeCycles.peek().litValue.toLong,
      "bubbleCycles"      -> p.bubbleCycles.peek().litValue.toLong,
      "ubReads"           -> p.ubReads.peek().litValue.toLong,
      "ubWrites"          -> p.ubWrites.peek().litValue.toLong,
      "dmaLoadCount"      -> p.dmaLoadCount.peek().litValue.toLong,
      "dmaStoreCount"     -> p.dmaStoreCount.peek().litValue.toLong,
      "dmaTotalCycles"    -> p.dmaTotalCycles.peek().litValue.toLong
    )
  }

  describe("PerfCounters") {

    it("counts NOP + HALT correctly (core 0)") {
      simulate(new ToyAscendTop) { dut =>
        initDut(dut)
        loadProgram(dut, Seq(encNop, encHalt))
        runToHalt(dut)

        val p = peekPerf(dut, 0)
        assert(p("instrNop") == 1)
        assert(p("instrHalt") == 1)
        assert(p("totalCycles") > 0)
        assert(p("cubeTotalCycles") == 0)
      }
    }

    it("counts DMA + MATMUL pipeline correctly (core 0)") {
      simulate(new ToyAscendTop) { dut =>
        initDut(dut)

        val a = Array(Array(1, 2, 3, 4), Array(5, 6, 7, 8),
                      Array(2, 3, 1, 4), Array(7, 1, 5, 3))
        val w = Array(Array(1, 0, 2, 1), Array(3, 1, 0, 2),
                      Array(2, 4, 1, 3), Array(0, 2, 3, 1))
        for (i <- 0 until N) writeL2(dut, i, a(i))
        for (i <- 0 until N) writeL2(dut, 4 + i, w(i))

        val program = Seq(
          encDmaLoad(0, 0), encDmaLoad(4, 4),
          encLoad(1, 0), encLoad(0, 4),
          encMatmul, encStore(2, 8),
          encDmaStore(8, 8), encHalt
        )
        loadProgram(dut, program)
        runToHalt(dut)

        val p = peekPerf(dut, 0)
        println(s"Perf: total=${p("totalCycles")} dma=${p("dmaTotalCycles")} cube=${p("cubeTotalCycles")}")
        assert(p("instrLoad") == 2)
        assert(p("instrMatmul") == 1)
        assert(p("instrStore") == 1)
        assert(p("dmaLoadCount") == 2)
        assert(p("dmaStoreCount") == 1)
        assert(p("cubeTotalCycles") > 0)
        assert(p("dmaTotalCycles") > 0)
      }
    }
  }
}
