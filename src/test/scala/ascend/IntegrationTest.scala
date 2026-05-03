package ascend

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec

class IntegrationTest extends AnyFunSpec with ChiselSim {

  val N = AscendParams.ArraySize

  def encLoad(bufSel: Int, memAddr: Int): Long =
    (0x2L << 28) | ((bufSel & 0x3).toLong << 26) | ((memAddr & 0xffff).toLong << 4)
  def encStore(bufSel: Int, memAddr: Int): Long =
    (0x3L << 28) | ((bufSel & 0x3).toLong << 26) | ((memAddr & 0xffff).toLong << 4)
  def encDmaLoad(ubBase: Int, l2Base: Int): Long =
    (0x8L << 28) | ((ubBase & 0xff).toLong << 20) | ((l2Base & 0xffff).toLong << 4)
  def encDmaStore(ubBase: Int, l2Base: Int): Long =
    (0x9L << 28) | ((ubBase & 0xff).toLong << 20) | ((l2Base & 0xffff).toLong << 4)
  def encDmaWait: Long = 0xaL << 28
  def encMatmul: Long = 0x4L << 28
  def encMatmulAcc: Long = (0x4L << 28) | (1L << 27)
  def encNop: Long = 0x0L
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

        // 动态生成 N×N 测试矩阵
        val a = Array.tabulate(N, N)((i, j) => (i + j + 1) % 10)
        val w = Array.tabulate(N, N)((i, j) => (i * 2 + j + 1) % 10)
        val expected = Array.tabulate(N, N)((i, j) => (0 until N).map(k => a(i)(k) * w(k)(j)).sum)

        // Preload L2 (core 0 slice: addr 0..N-1 for A, N..2N-1 for W)
        for (i <- 0 until N) writeL2(dut, i, a(i))
        for (i <- 0 until N) writeL2(dut, N + i, w(i))

        val program = Seq(
          encDmaLoad(ubBase = 0, l2Base = 0), // L2[0..N-1] → UB[0..N-1] (non-blocking)
          encDmaLoad(ubBase = N, l2Base = N), // L2[N..2N-1] → UB[N..2N-1] (non-blocking)
          encDmaWait, // Wait for DMAs to complete
          encLoad(1, 0), // UB[0..N-1] → act_buf (L0A)
          encLoad(0, N), // UB[N..2N-1] → weight_buf (L0B)
          encMatmul, // L0A × L0B → L0C
          encStore(2, 2 * N), // L0C → UB[2N..3N-1]
          encDmaStore(ubBase = 2 * N, l2Base = 2 * N), // UB[2N..3N-1] → L2[2N..3N-1] (non-blocking)
          encDmaWait, // Wait for DMA_STORE to complete
          encHalt
        )
        loadProgram(dut, program)
        val cycles = runToHalt(dut)

        // Read result from L2 (core 0 slice: addr 2N..3N-1)
        for (i <- 0 until N) {
          val row = readL2(dut, 2 * N + i)
          for (j <- 0 until N) {
            assert(
              row(j) == expected(i)(j),
              s"C[$i][$j]: got ${row(j)}, expected ${expected(i)(j)}"
            )
          }
        }

        // 性能统计
        val perf = dut.io.perf(0).peek()
        val totalCycles = perf.totalCycles.litValue.toInt
        val cubeComputeCycles = perf.cubeComputeCycles.litValue.toInt
        val dmaTotalCycles = perf.dmaTotalCycles.litValue.toInt
        val copyInCycles = perf.copyInCycles.litValue.toInt
        val copyOutCycles = perf.copyOutCycles.litValue.toInt
        val bubbleCycles = perf.bubbleCycles.litValue.toInt
        val overlapCycles = perf.overlapCycles.litValue.toInt
        val dataflowOverlapCycles = perf.dataflowOverlapCycles.litValue.toInt

        println(s"\n=== NPU 性能统计 (${N}×${N} 矩阵乘法) ===")
        println(f"总周期数:           $totalCycles%4d")
        println(f"Cube 计算周期:      $cubeComputeCycles%4d")
        println(f"MTE2 DMA 周期:      $dmaTotalCycles%4d")
        println(f"CopyIn 周期:        $copyInCycles%4d")
        println(f"CopyOut 周期:       $copyOutCycles%4d")
        println(f"MTE/Cube 重叠周期:  $overlapCycles%4d")
        println(f"数据流重叠周期:     $dataflowOverlapCycles%4d")
        println(f"气泡周期:           $bubbleCycles%4d")
        println(f"计算效率:           ${cubeComputeCycles * 100.0 / totalCycles}%.1f%%")
        println(f"DMA 占比:           ${dmaTotalCycles * 100.0 / totalCycles}%.1f%%")
        println(f"重叠率:             ${if (dmaTotalCycles > 0) overlapCycles * 100.0 / dmaTotalCycles
          else 0.0}%.1f%%")
        println(
          f"理论峰值利用率:     ${cubeComputeCycles * 100.0 / (cubeComputeCycles + bubbleCycles)}%.1f%%"
        )
      }
    }

    it("accumulates two MATMUL tiles into L0C") {
      simulate(new ToyAscendTop) { dut =>
        initDut(dut)

        val a = Array.tabulate(N, N)((i, j) => (i + j + 2) % 7)
        val w = Array.tabulate(N, N)((i, j) => if (i == j) 1 else (i * 2 + j) % 4)
        val single = Array.tabulate(N, N)((i, j) => (0 until N).map(k => a(i)(k) * w(k)(j)).sum)
        val expected = Array.tabulate(N, N)((i, j) => single(i)(j) * 2)

        for (i <- 0 until N) writeL2(dut, i, a(i))
        for (i <- 0 until N) writeL2(dut, N + i, w(i))

        val program = Seq(
          encDmaLoad(ubBase = 0, l2Base = 0),
          encDmaLoad(ubBase = N, l2Base = N),
          encDmaWait,
          encLoad(1, 0),
          encLoad(0, N),
          encMatmul,
          encLoad(1, 0),
          encLoad(0, N),
          encMatmulAcc,
          encStore(2, 2 * N),
          encDmaStore(ubBase = 2 * N, l2Base = 2 * N),
          encDmaWait,
          encHalt
        )
        loadProgram(dut, program)
        runToHalt(dut, maxCycles = 700)

        for (i <- 0 until N) {
          val row = readL2(dut, 2 * N + i)
          for (j <- 0 until N) {
            assert(
              row(j) == expected(i)(j),
              s"C[$i][$j]: got ${row(j)}, expected ${expected(i)(j)}"
            )
          }
        }
      }
    }
  }
}
