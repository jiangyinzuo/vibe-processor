package ascend

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec

class SpmdTest extends AnyFunSpec with ChiselSim {

  val N  = AscendParams.ArraySize
  val AW = AscendParams.AccWidth
  val BlockStride = 64

  def encDmaLoad(ubBase: Int, l2Base: Int): Long =
    (0x8L << 28) | ((ubBase & 0xFF).toLong << 20) | ((l2Base & 0xFFFF).toLong << 4)
  def encDmaStore(ubBase: Int, l2Base: Int): Long =
    (0x9L << 28) | ((ubBase & 0xFF).toLong << 20) | ((l2Base & 0xFFFF).toLong << 4)
  def encDmaWait: Long = 0xAL << 28
  def encLoad(bufSel: Int, memAddr: Int): Long =
    (0x2L << 28) | ((bufSel & 0x3).toLong << 26) | ((memAddr & 0xFFFF).toLong << 4)
  def encStore(bufSel: Int, memAddr: Int): Long =
    (0x3L << 28) | ((bufSel & 0x3).toLong << 26) | ((memAddr & 0xFFFF).toLong << 4)
  def encMatmul: Long = 0x4L << 28
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

  def readL2(dut: ToyAscendTop, addr: Int): Array[Int] = {
    dut.io.l2Ext.en.poke(true.B)
    dut.io.l2Ext.we.poke(false.B)
    dut.io.l2Ext.addr.poke(addr.U)
    dut.clock.step()
    dut.io.l2Ext.en.poke(false.B)
    val result = Array.tabulate(N)(j => dut.io.l2Ext.rdata(j).peek().litValue.toInt)
    dut.clock.step()
    result
  }

  def initDut(dut: ToyAscendTop, blockDim: Int): Unit = {
    dut.io.start.poke(false.B)
    dut.io.blockDim.poke(blockDim.U)
    dut.io.imemLoadEn.poke(false.B)
    dut.io.l2Ext.en.poke(false.B)
    dut.io.l2Ext.we.poke(false.B)
    dut.io.hbmExt.en.poke(false.B)
    dut.io.hbmExt.we.poke(false.B)
  }

  def runToHalt(dut: ToyAscendTop, maxCycles: Int = 1200): Int = {
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

  describe("Ascend SPMD block scheduling") {
    it("runs blockDim logical blocks on fewer physical cores") {
      simulate(new ToyAscendTop(numCores = 2, blockStride = BlockStride, hbmLatency = 10)) { dut =>
        initDut(dut, blockDim = 4)

        val tiles = Array.tabulate(4) { b =>
          (
            Array.tabulate(N, N)((i, j) => (b + i + j + 1) % 8),
            Array.tabulate(N, N)((i, j) => (b * 2 + i + j + 1) % 8)
          )
        }

        for (b <- tiles.indices) {
          val (a, w) = tiles(b)
          val base = b * BlockStride
          for (i <- 0 until N) writeL2(dut, base + i, a(i))
          for (i <- 0 until N) writeL2(dut, base + N + i, w(i))
        }

        val program = Seq(
          encDmaLoad(ubBase = 0, l2Base = 0),
          encDmaLoad(ubBase = N, l2Base = N),
          encDmaWait,
          encLoad(1, 0),
          encLoad(0, N),
          encMatmul,
          encStore(2, 2 * N),
          encDmaStore(ubBase = 2 * N, l2Base = 2 * N),
          encDmaWait,
          encHalt
        )
        loadProgram(dut, program)

        val cycles = runToHalt(dut)
        println(s"SPMD blockDim=4 on 2 physical cores: $cycles cycles")

        for (b <- tiles.indices) {
          val (a, w) = tiles(b)
          val expected = Array.tabulate(N, N)((i, j) =>
            (0 until N).map(k => a(i)(k) * w(k)(j)).sum
          )
          val base = b * BlockStride + 2 * N

          for (i <- 0 until N) {
            val row = readL2(dut, base + i)
            for (j <- 0 until N) {
              assert(row(j) == expected(i)(j),
                s"Block $b, C[$i][$j]: got ${row(j)}, expected ${expected(i)(j)}")
            }
          }
        }

        val starts = (0 until 2).map(c => dut.io.perf(c).blockStarts.peek().litValue.toInt).sum
        val completions = (0 until 2).map(c => dut.io.perf(c).blockCompletions.peek().litValue.toInt).sum
        assert(starts == 4, s"expected 4 SPMD block starts, got $starts")
        assert(completions == 4, s"expected 4 SPMD block completions, got $completions")
      }
    }
  }
}
