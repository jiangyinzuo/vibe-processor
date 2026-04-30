package ascend

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec

class DmaTest extends AnyFunSpec with ChiselSim {

  val N  = AscendParams.ArraySize
  val AW = AscendParams.AccWidth

  /** Encode DMA_LOAD: [31:28]=0x8, [27:20]=ub_base, [19:4]=hbm_base */
  def encDmaLoad(ubBase: Int, hbmBase: Int): Long =
    (0x8L << 28) | ((ubBase & 0xFF).toLong << 20) | ((hbmBase & 0xFFFF).toLong << 4)

  /** Encode DMA_STORE: [31:28]=0x9, [27:20]=ub_base, [19:4]=hbm_base */
  def encDmaStore(ubBase: Int, hbmBase: Int): Long =
    (0x9L << 28) | ((ubBase & 0xFF).toLong << 20) | ((hbmBase & 0xFFFF).toLong << 4)

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

  def writeHbm(dut: ToyAscendTop, addr: Int, row: Array[Int]): Unit = {
    dut.io.hbmExt.en.poke(true.B)
    dut.io.hbmExt.we.poke(true.B)
    dut.io.hbmExt.addr.poke(addr.U)
    for (j <- 0 until N) dut.io.hbmExt.wdata(j).poke(row(j).S(AW.W))
    dut.clock.step()
    dut.io.hbmExt.en.poke(false.B)
    dut.io.hbmExt.we.poke(false.B)
    dut.clock.step()
  }

  def readHbm(dut: ToyAscendTop, addr: Int): Array[Int] = {
    dut.io.hbmExt.en.poke(true.B)
    dut.io.hbmExt.we.poke(false.B)
    dut.io.hbmExt.addr.poke(addr.U)
    dut.clock.step()
    dut.io.hbmExt.en.poke(false.B)
    dut.clock.step()
    Array.tabulate(N)(j => dut.io.hbmExt.rdata(j).peek().litValue.toInt)
  }

  def readUb(dut: ToyAscendTop, addr: Int): Array[Int] = {
    dut.io.ubExt.en.poke(true.B)
    dut.io.ubExt.we.poke(false.B)
    dut.io.ubExt.addr.poke(addr.U)
    dut.clock.step()
    dut.io.ubExt.en.poke(false.B)
    dut.clock.step()
    Array.tabulate(N)(j => dut.io.ubExt.rdata(j).peek().litValue.toInt)
  }

  def initDut(dut: ToyAscendTop): Unit = {
    dut.io.start.poke(false.B)
    dut.io.imemLoadEn.poke(false.B)
    dut.io.ubExt.en.poke(false.B)
    dut.io.ubExt.we.poke(false.B)
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

  describe("DMA") {

    it("DMA_LOAD: transfers data from HBM to UB") {
      simulate(new ToyAscendTop(hbmLatency = 5)) { dut =>
        initDut(dut)

        // Preload HBM[0..3] with test data
        for (i <- 0 until N) {
          writeHbm(dut, i, Array.tabulate(N)(j => (i + 1) * 10 + j))
        }

        // Program: DMA_LOAD from HBM[0] to UB[0], then HALT
        loadProgram(dut, Seq(encDmaLoad(ubBase = 0, hbmBase = 0), encHalt))

        val cycles = runToHalt(dut)
        println(s"DMA_LOAD: halted after $cycles cycles")

        // Verify UB[0..3] contains HBM data
        for (i <- 0 until N) {
          val row = readUb(dut, i)
          val expected = Array.tabulate(N)(j => (i + 1) * 10 + j)
          assert(row.toSeq == expected.toSeq,
            s"UB[$i]: got ${row.mkString(",")}, expected ${expected.mkString(",")}")
        }
      }
    }

    it("DMA_STORE: transfers data from UB to HBM") {
      simulate(new ToyAscendTop(hbmLatency = 5)) { dut =>
        initDut(dut)

        // Preload HBM[0..3] (source data)
        val a = Array(Array(1, 2, 3, 4), Array(5, 6, 7, 8),
                      Array(2, 3, 1, 4), Array(7, 1, 5, 3))
        val w = Array(Array(1, 0, 2, 1), Array(3, 1, 0, 2),
                      Array(2, 4, 1, 3), Array(0, 2, 3, 1))
        for (i <- 0 until N) writeHbm(dut, i, a(i))
        for (i <- 0 until N) writeHbm(dut, 4 + i, w(i))

        // Program: DMA_LOAD A from HBM[0]→UB[0], DMA_LOAD W from HBM[4]→UB[4]
        //          LOAD UB→internal, MATMUL, STORE internal→UB
        //          DMA_STORE UB[8]→HBM[100], HALT
        val program = Seq(
          encDmaLoad(ubBase = 0, hbmBase = 0),   // HBM[0..3] → UB[0..3]
          encDmaLoad(ubBase = 4, hbmBase = 4),   // HBM[4..7] → UB[4..7]
          encLoad(1, 0),                           // UB[0..3] → act_buf (L0_B)
          encLoad(0, 4),                           // UB[4..7] → weight_buf (L0_A)
          encMatmul,                               // C = A * W
          encStore(2, 8),                          // result → UB[8..11]
          encDmaStore(ubBase = 8, hbmBase = 100), // UB[8..11] → HBM[100..103]
          encHalt
        )
        loadProgram(dut, program)

        val cycles = runToHalt(dut)

        // Read result from HBM[100..103]
        val expected = Array.tabulate(N, N)((i, j) =>
          (0 until N).map(k => a(i)(k) * w(k)(j)).sum
        )

        println(s"Full pipeline (HBM→DMA→UB→MATMUL→UB→DMA→HBM): $cycles cycles")
        val dmaC = dut.io.perf.dmaTotalCycles.peek().litValue.toLong
        val total = dut.io.perf.totalCycles.peek().litValue.toLong
        println(s"  DMA total cycles: $dmaC / $total total")

        for (i <- 0 until N) {
          val row = readHbm(dut, 100 + i)
          for (j <- 0 until N) {
            assert(row(j) == expected(i)(j),
              s"HBM[${100 + i}][$j]: got ${row(j)}, expected ${expected(i)(j)}")
          }
        }
      }
    }
  }
}
