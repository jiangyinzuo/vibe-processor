package ascend

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec

/** 对比测试：顺序执行 vs 流水线 Overlap 展示 DMA-Compute Overlap 的实际性能提升
  */
class OverlapBenchmark extends AnyFunSpec with ChiselSim {

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

  def runToHalt(dut: ToyAscendTop, maxCycles: Int = 3000): Int = {
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

  describe("DMA-Compute Overlap 性能对比") {

    it("基准测试：顺序执行（无 Overlap）") {
      simulate(new ToyAscendTop(numCores = 1, hbmLatency = 10)) { dut =>
        initDut(dut)

        val numTiles = 3
        val tiles = Array.tabulate(numTiles) { t =>
          val a = Array.tabulate(N, N)((i, j) => ((t + 1) * (i + j + 1)) % 8)
          val w = Array.tabulate(N, N)((i, j) => ((t + 1) * (i * 2 + j)) % 8)
          (a, w)
        }

        for (t <- 0 until numTiles) {
          val (a, w) = tiles(t)
          for (i <- 0 until N) {
            writeL2(dut, t * 2 * N + i, a(i))
            writeL2(dut, t * 2 * N + N + i, w(i))
          }
        }

        val programSequential = (0 until numTiles).flatMap { t =>
          Seq(
            encDmaLoad(ubBase = 0, l2Base = t * 2 * N),
            encDmaLoad(ubBase = N, l2Base = t * 2 * N + N),
            encDmaWait,
            encLoad(1, 0),
            encLoad(0, N),
            encMatmul,
            encStore(2, 2 * N),
            encDmaStore(ubBase = 2 * N, l2Base = (numTiles * 2 + t) * N),
            encDmaWait
          )
        } :+ encHalt

        loadProgram(dut, programSequential)
        val cyclesSequential = runToHalt(dut)

        for (t <- 0 until numTiles) {
          val (a, w) = tiles(t)
          val expected = Array.tabulate(N, N)((i, j) => (0 until N).map(k => a(i)(k) * w(k)(j)).sum)
          val result = Array.tabulate(N)(i => readL2(dut, (numTiles * 2 + t) * N + i))

          for (i <- 0 until N; j <- 0 until N) {
            assert(
              result(i)(j) == expected(i)(j),
              s"Sequential Tile $t mismatch at [$i][$j]: got ${result(i)(j)}, expected ${expected(i)(j)}"
            )
          }
        }

        val perf = dut.io.perf(0).peek()
        val cubeComputeCycles = perf.cubeComputeCycles.litValue.toInt
        val dmaTotalCycles = perf.dmaTotalCycles.litValue.toInt
        val copyInCycles = perf.copyInCycles.litValue.toInt
        val copyOutCycles = perf.copyOutCycles.litValue.toInt
        val overlapCycles = perf.overlapCycles.litValue.toInt
        val dataflowOverlapCycles = perf.dataflowOverlapCycles.litValue.toInt

        println("\n" + "=" * 70)
        println("基准测试：顺序执行（无 Overlap）")
        println("=" * 70)
        println(f"Tile 数量:              $numTiles")
        println(f"总周期数:               $cyclesSequential%5d")
        println(f"Cube 计算周期:          $cubeComputeCycles%5d")
        println(f"MTE2 DMA 周期:          $dmaTotalCycles%5d")
        println(f"CopyIn 周期:            $copyInCycles%5d")
        println(f"CopyOut 周期:           $copyOutCycles%5d")
        println(f"MTE/Cube 重叠周期:      $overlapCycles%5d")
        println(f"数据流重叠周期:         $dataflowOverlapCycles%5d")
        println(f"计算效率:               ${cubeComputeCycles * 100.0 / cyclesSequential}%.1f%%")
        println(f"DMA 占比:               ${dmaTotalCycles * 100.0 / cyclesSequential}%.1f%%")
        println(
          f"重叠率:                 ${if (dmaTotalCycles > 0) overlapCycles * 100.0 / dmaTotalCycles
            else 0.0}%.1f%%"
        )
        println(f"每个 Tile 平均周期:     ${cyclesSequential.toDouble / numTiles}%.1f")
        println("=" * 70)
      }
    }

    it("优化测试：流水线 Overlap") {
      simulate(new ToyAscendTop(numCores = 1, hbmLatency = 10)) { dut =>
        initDut(dut)

        val numTiles = 3
        val tiles = Array.tabulate(numTiles) { t =>
          val a = Array.tabulate(N, N)((i, j) => ((t + 1) * (i + j + 1)) % 8)
          val w = Array.tabulate(N, N)((i, j) => ((t + 1) * (i * 2 + j)) % 8)
          (a, w)
        }

        for (t <- 0 until numTiles) {
          val (a, w) = tiles(t)
          for (i <- 0 until N) {
            writeL2(dut, t * 2 * N + i, a(i))
            writeL2(dut, t * 2 * N + N + i, w(i))
          }
        }

        val programPipelined = Seq(
          // Tile 0: 初始加载
          encDmaLoad(ubBase = 0, l2Base = 0),
          encDmaLoad(ubBase = N, l2Base = N),
          encDmaWait,
          encLoad(1, 0),
          encLoad(0, N)
        ) ++ (1 until numTiles).flatMap { t =>
          Seq(
            // 预取下一个 tile（非阻塞）
            encDmaLoad(ubBase = 0, l2Base = t * 2 * N),
            encDmaLoad(ubBase = N, l2Base = t * 2 * N + N),
            // 计算当前 tile（与 DMA 重叠）
            encMatmul,
            encStore(2, 2 * N),
            encDmaStore(ubBase = 2 * N, l2Base = (numTiles * 2 + t - 1) * N),
            // 等待预取完成
            encDmaWait,
            encLoad(1, 0),
            encLoad(0, N)
          )
        } ++ Seq(
          // 最后一个 tile 计算
          encMatmul,
          encStore(2, 2 * N),
          encDmaStore(ubBase = 2 * N, l2Base = (numTiles * 2 + numTiles - 1) * N),
          encDmaWait,
          encHalt
        )

        loadProgram(dut, programPipelined)
        val cyclesPipelined = runToHalt(dut)

        for (t <- 0 until numTiles) {
          val (a, w) = tiles(t)
          val expected = Array.tabulate(N, N)((i, j) => (0 until N).map(k => a(i)(k) * w(k)(j)).sum)
          val result = Array.tabulate(N)(i => readL2(dut, (numTiles * 2 + t) * N + i))

          for (i <- 0 until N; j <- 0 until N) {
            assert(
              result(i)(j) == expected(i)(j),
              s"Pipelined Tile $t mismatch at [$i][$j]: got ${result(i)(j)}, expected ${expected(i)(j)}"
            )
          }
        }

        val perf = dut.io.perf(0).peek()
        val cubeComputeCycles = perf.cubeComputeCycles.litValue.toInt
        val dmaTotalCycles = perf.dmaTotalCycles.litValue.toInt
        val copyInCycles = perf.copyInCycles.litValue.toInt
        val copyOutCycles = perf.copyOutCycles.litValue.toInt
        val overlapCycles = perf.overlapCycles.litValue.toInt
        val dataflowOverlapCycles = perf.dataflowOverlapCycles.litValue.toInt

        println("\n" + "=" * 70)
        println("优化测试：流水线 Overlap")
        println("=" * 70)
        println(f"Tile 数量:              $numTiles")
        println(f"总周期数:               $cyclesPipelined%5d")
        println(f"Cube 计算周期:          $cubeComputeCycles%5d")
        println(f"MTE2 DMA 周期:          $dmaTotalCycles%5d")
        println(f"CopyIn 周期:            $copyInCycles%5d")
        println(f"CopyOut 周期:           $copyOutCycles%5d")
        println(f"MTE/Cube 重叠周期:      $overlapCycles%5d")
        println(f"数据流重叠周期:         $dataflowOverlapCycles%5d")
        println(f"计算效率:               ${cubeComputeCycles * 100.0 / cyclesPipelined}%.1f%%")
        println(f"DMA 占比:               ${dmaTotalCycles * 100.0 / cyclesPipelined}%.1f%%")
        println(
          f"重叠率:                 ${if (dmaTotalCycles > 0) overlapCycles * 100.0 / dmaTotalCycles
            else 0.0}%.1f%%"
        )
        println(f"每个 Tile 平均周期:     ${cyclesPipelined.toDouble / numTiles}%.1f")
        println("=" * 70)
      }
    }
  }
}
