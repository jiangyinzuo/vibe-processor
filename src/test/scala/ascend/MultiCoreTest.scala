package ascend

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec

class MultiCoreTest extends AnyFunSpec with ChiselSim {

  val N = AscendParams.ArraySize
  val AW = AscendParams.AccWidth
  val NC = AscendParams.NumCores
  val SLICE = AscendParams.L2SliceSize

  def encDmaLoad(ubBase: Int, l2Base: Int): Long =
    (0x8L << 28) | ((ubBase & 0xff).toLong << 20) | ((l2Base & 0xffff).toLong << 4)
  def encDmaStore(ubBase: Int, l2Base: Int): Long =
    (0x9L << 28) | ((ubBase & 0xff).toLong << 20) | ((l2Base & 0xffff).toLong << 4)
  def encDmaWait: Long = 0xaL << 28
  def encLoad(bufSel: Int, memAddr: Int): Long =
    (0x2L << 28) | ((bufSel & 0x3).toLong << 26) | ((memAddr & 0xffff).toLong << 4)
  def encStore(bufSel: Int, memAddr: Int): Long =
    (0x3L << 28) | ((bufSel & 0x3).toLong << 26) | ((memAddr & 0xffff).toLong << 4)
  def encMatmul: Long = 0x4L << 28
  def encHalt: Long = 0x1L << 28

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

  def initDut(dut: ToyAscendTop): Unit = {
    dut.io.start.poke(false.B)
    dut.io.blockDim.poke(0.U)
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

  describe("Multi-core NPU") {

    it("2 cores each compute MATMUL on different data tiles") {
      simulate(new ToyAscendTop) { dut =>
        initDut(dut)

        // Test data: 2 different matrix pairs
        val tiles = Array(
          // Tile 0 (core 0): A0, W0
          (
            Array.tabulate(N, N)((i, j) => (i + j + 1) % 8),
            Array.tabulate(N, N)((i, j) => (i * 2 + j) % 8)
          ),
          // Tile 1 (core 1): A1, W1
          (
            Array.tabulate(N, N)((i, j) => (i + j + 2) % 8),
            Array.tabulate(N, N)((i, j) => (i * 2 + j + 1) % 8)
          )
        )

        // Preload L2: Core 0 data at L2[0..2N-1], Core 1 data at L2[SLICE..SLICE+2N-1]
        for (c <- 0 until NC) {
          val (a, w) = tiles(c)
          val base = c * SLICE
          for (i <- 0 until N) writeL2(dut, base + i, a(i))
          for (i <- 0 until N) writeL2(dut, base + N + i, w(i))
        }

        // Program (shared, both cores run this):
        val program = Seq(
          encDmaLoad(ubBase = 0, l2Base = 0),
          encDmaLoad(ubBase = N, l2Base = N),
          encDmaWait,
          encLoad(1, 0), // L0_B from UB[0]
          encLoad(0, N), // L0_A from UB[N]
          encMatmul,
          encStore(2, 2 * N), // result to UB[2*N]
          encDmaStore(ubBase = 2 * N, l2Base = 2 * N),
          encDmaWait,
          encHalt
        )
        loadProgram(dut, program)

        val cycles = runToHalt(dut)
        println(s"Multi-core MATMUL: $cycles cycles")

        // Verify results from L2
        for (c <- 0 until NC) {
          val (a, w) = tiles(c)
          val expected = Array.tabulate(N, N)((i, j) => (0 until N).map(k => a(i)(k) * w(k)(j)).sum)
          val base = c * SLICE + 2 * N

          for (i <- 0 until N) {
            val row = readL2(dut, base + i)
            for (j <- 0 until N) {
              assert(
                row(j) == expected(i)(j),
                s"Core $c, C[$i][$j]: got ${row(j)}, expected ${expected(i)(j)}"
              )
            }
          }
          println(s"  Core $c result verified OK")
        }

        // Print per-core perf
        for (c <- 0 until NC) {
          val total = dut.io.perf(c).totalCycles.peek().litValue.toLong
          val dmaT = dut.io.perf(c).dmaTotalCycles.peek().litValue.toLong
          val cubeT = dut.io.perf(c).cubeTotalCycles.peek().litValue.toLong
          println(s"  Core $c: total=$total, dma=$dmaT, cube=$cubeT")
        }
      }
    }
  }
}
