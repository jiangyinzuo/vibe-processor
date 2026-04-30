package ascend

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec

class IntegrationTest extends AnyFunSpec with ChiselSim {

  val N = AscendParams.ArraySize

  def encLoad(bufSel: Int, memAddr: Int): Long =
    (0x2L << 28) | ((bufSel & 0x3).toLong << 26) | ((memAddr & 0xFFFF).toLong << 4)
  def encStore(bufSel: Int, memAddr: Int): Long =
    (0x3L << 28) | ((bufSel & 0x3).toLong << 26) | ((memAddr & 0xFFFF).toLong << 4)
  def encDmaLoad(ubBase: Int, l2Base: Int): Long =
    (0x8L << 28) | ((ubBase & 0xFF).toLong << 20) | ((l2Base & 0xFFFF).toLong << 4)
  def encDmaStore(ubBase: Int, l2Base: Int): Long =
    (0x9L << 28) | ((ubBase & 0xFF).toLong << 20) | ((l2Base & 0xFFFF).toLong << 4)
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
    for (j <- 0 until N) dut.io.l2Ext.wdata(j).poke(row(j).S(32.W))
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

  describe("Integration") {

    it("runs NOP + HALT program") {
      simulate(new ToyAscendTop) { dut =>
        initDut(dut)
        loadProgram(dut, Seq(encNop, encHalt))
        runToHalt(dut)
      }
    }

    it("runs DMA_LOAD -> LOAD -> MATMUL -> STORE -> DMA_STORE, verifies C = A * W") {
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
        val expected = Array.tabulate(N, N)((i, j) =>
          (0 until N).map(k => a(i)(k) * w(k)(j)).sum
        )

        // Preload L2 (core 0 slice: addr 0..7)
        for (i <- 0 until N) writeL2(dut, i, a(i))
        for (i <- 0 until N) writeL2(dut, 4 + i, w(i))

        val program = Seq(
          encDmaLoad(ubBase = 0, l2Base = 0),   // L2[0..3] → UB[0..3]
          encDmaLoad(ubBase = 4, l2Base = 4),   // L2[4..7] → UB[4..7]
          encLoad(1, 0),                         // UB → act_buf
          encLoad(0, 4),                         // UB → weight_buf
          encMatmul,
          encStore(2, 8),                        // result → UB[8..11]
          encDmaStore(ubBase = 8, l2Base = 8),  // UB[8..11] → L2[8..11]
          encHalt
        )
        loadProgram(dut, program)
        val cycles = runToHalt(dut)

        // Read result from L2 (core 0 slice: addr 8..11)
        for (i <- 0 until N) {
          val row = readL2(dut, 8 + i)
          for (j <- 0 until N) {
            assert(row(j) == expected(i)(j),
              s"C[$i][$j]: got ${row(j)}, expected ${expected(i)(j)}")
          }
        }
      }
    }
  }
}
