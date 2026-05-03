package ascend

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec

/** 三级流水线测试：验证 LOAD 和 MATMUL 分离后的性能提升
  */
class Pipeline3Test extends AnyFunSpec with ChiselSim {

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

  def runToHalt(dut: ToyAscendTop, maxCycles: Int = 2500): Int = {
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

  describe("三级流水线性能测试") {

    it("验证 LOAD 提前执行的性能提升") {
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

        // 三级流水线程序：LOAD 提前，与前一个 MATMUL 重叠
        val program3Stage = Seq(
          // Tile 0: 初始加载
          encDmaLoad(ubBase = 0, l2Base = 0),
          encDmaLoad(ubBase = N, l2Base = N),
          encDmaWait,
          encLoad(1, 0),
          encLoad(0, N),

          // Tile 1: 预取 + 提前 LOAD
          encDmaLoad(ubBase = 0, l2Base = 2 * N),
          encDmaLoad(ubBase = N, l2Base = 3 * N),
          encDmaWait,
          encLoad(1, 0), // ✅ 提前 LOAD，与 Tile 0 的 MATMUL 重叠
          encLoad(0, N), // ✅ 提前 LOAD，与 Tile 0 的 MATMUL 重叠
          encMatmul, // 计算 Tile 0
          encStore(2, 2 * N),
          encDmaStore(ubBase = 2 * N, l2Base = (numTiles * 2) * N),

          // Tile 2: 预取 + 提前 LOAD
          encDmaLoad(ubBase = 0, l2Base = 4 * N),
          encDmaLoad(ubBase = N, l2Base = 5 * N),
          encDmaWait,
          encLoad(1, 0), // ✅ 提前 LOAD，与 Tile 1 的 MATMUL 重叠
          encLoad(0, N), // ✅ 提前 LOAD，与 Tile 1 的 MATMUL 重叠
          encMatmul, // 计算 Tile 1
          encStore(2, 2 * N),
          encDmaStore(ubBase = 2 * N, l2Base = (numTiles * 2 + 1) * N),

          // 最后一个 tile
          encMatmul, // 计算 Tile 2
          encStore(2, 2 * N),
          encDmaStore(ubBase = 2 * N, l2Base = (numTiles * 2 + 2) * N),
          encDmaWait,
          encHalt
        )

        loadProgram(dut, program3Stage)
        val cycles = runToHalt(dut)

        // 验证结果
        for (t <- 0 until numTiles) {
          val (a, w) = tiles(t)
          val expected = Array.tabulate(N, N)((i, j) => (0 until N).map(k => a(i)(k) * w(k)(j)).sum)
          val result = Array.tabulate(N)(i => readL2(dut, (numTiles * 2 + t) * N + i))

          for (i <- 0 until N; j <- 0 until N) {
            assert(
              result(i)(j) == expected(i)(j),
              s"Tile $t mismatch at [$i][$j]: got ${result(i)(j)}, expected ${expected(i)(j)}"
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
        println("三级流水线性能测试")
        println("=" * 70)
        println(f"Tile 数量:              $numTiles")
        println(f"总周期数:               $cycles%5d")
        println(f"Cube 计算周期:          $cubeComputeCycles%5d")
        println(f"MTE2 DMA 周期:          $dmaTotalCycles%5d")
        println(f"CopyIn 周期:            $copyInCycles%5d")
        println(f"CopyOut 周期:           $copyOutCycles%5d")
        println(f"MTE/Cube 重叠周期:      $overlapCycles%5d")
        println(f"数据流重叠周期:         $dataflowOverlapCycles%5d")
        println(f"计算效率:               ${cubeComputeCycles * 100.0 / cycles}%.1f%%")
        println(f"DMA 占比:               ${dmaTotalCycles * 100.0 / cycles}%.1f%%")
        println(
          f"重叠率:                 ${if (dmaTotalCycles > 0) overlapCycles * 100.0 / dmaTotalCycles
            else 0.0}%.1f%%"
        )
        println(f"每个 Tile 平均周期:     ${cycles.toDouble / numTiles}%.1f")
        println("=" * 70)
        println("说明：")
        println("  - LOAD 指令提前执行，与前一个 MATMUL 重叠")
        println("  - 三级流水线：DMA → LOAD → MATMUL")
        println("  - 预期重叠周期应该显著高于 52")
        println("=" * 70)

        // 断言：重叠率应该显著提升
        assert(overlapCycles > 52, s"Expected overlap > 52, but got $overlapCycles")
      }
    }
  }
}
