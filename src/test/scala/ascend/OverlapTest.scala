package ascend

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec

/** 测试 DMA 与 Compute 的重叠效果 */
class OverlapTest extends AnyFunSpec with ChiselSim {

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

  def runToHalt(dut: ToyAscendTop, maxCycles: Int = 600): Int = {
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

  describe("DMA-Compute Overlap") {

    it("demonstrates overlap with pipelined tiles") {
      simulate(new ToyAscendTop(numCores = 1, hbmLatency = 10)) { dut =>
        initDut(dut)

        // 准备 2 个 tile 的数据
        val a0 = Array.tabulate(N, N)((i, j) => (i + j + 1) % 8)
        val w0 = Array.tabulate(N, N)((i, j) => (i * 2 + j) % 8)
        val a1 = Array.tabulate(N, N)((i, j) => (i + j + 2) % 8)
        val w1 = Array.tabulate(N, N)((i, j) => (i * 2 + j + 1) % 8)

        // 写入 L2
        for (i <- 0 until N) {
          writeL2(dut, i, a0(i)) // A0 at 0..N-1
          writeL2(dut, N + i, w0(i)) // W0 at N..2N-1
          writeL2(dut, 2 * N + i, a1(i)) // A1 at 2N..3N-1
          writeL2(dut, 3 * N + i, w1(i)) // W1 at 3N..4N-1
        }

        // 单程序流水线：展示 compute tile 0 时预取 tile 1
        val program = Seq(
          // === Tile 0: 初始加载 ===
          encDmaLoad(ubBase = 0, l2Base = 0), // Load A0 (non-blocking)
          encDmaLoad(ubBase = N, l2Base = N), // Load W0 (non-blocking)
          encDmaWait, // Wait for tile 0 data
          encLoad(1, 0), // UB -> L0B (act)
          encLoad(0, N), // UB -> L0A (weight)

          // === Tile 1: 预取 + 计算 tile 0（展示 overlap）===
          encDmaLoad(ubBase = 0, l2Base = 2 * N), // Prefetch A1 (non-blocking)
          encDmaLoad(ubBase = N, l2Base = 3 * N), // Prefetch W1 (non-blocking)
          encMatmul, // Compute tile 0 (与 DMA 重叠!)
          encStore(2, 4 * N), // Store result 0 to UB
          encDmaStore(ubBase = 4 * N, l2Base = 4 * N), // Write result 0 to L2
          encDmaWait, // Wait for tile 1 data and DMA_STORE
          encLoad(1, 0), // Load tile 1 to L0
          encLoad(0, N),

          // === Tile 1: 计算 ===
          encMatmul, // Compute tile 1
          encStore(2, 5 * N), // Store result 1 to UB
          encDmaStore(ubBase = 5 * N, l2Base = 5 * N), // Write result 1 to L2
          encDmaWait, // Wait for DMA_STORE

          encHalt
        )

        loadProgram(dut, program)
        val totalCycles = runToHalt(dut, 2000)

        // 验证结果
        val tiles = Array((a0, w0), (a1, w1))
        for (t <- 0 until 2) {
          val (a, w) = tiles(t)
          val expected = Array.tabulate(N, N)((i, j) => (0 until N).map(k => a(i)(k) * w(k)(j)).sum)
          val result = Array.tabulate(N)(i => readL2(dut, (4 + t) * N + i))

          for (i <- 0 until N; j <- 0 until N) {
            assert(
              result(i)(j) == expected(i)(j),
              s"Tile $t mismatch at [$i][$j]: got ${result(i)(j)}, expected ${expected(i)(j)}"
            )
          }
        }

        // 性能统计
        val perf = dut.io.perf(0).peek()
        val cubeComputeCycles = perf.cubeComputeCycles.litValue.toInt
        val dmaTotalCycles = perf.dmaTotalCycles.litValue.toInt
        val copyInCycles = perf.copyInCycles.litValue.toInt
        val copyOutCycles = perf.copyOutCycles.litValue.toInt
        val overlapCycles = perf.overlapCycles.litValue.toInt
        val dataflowOverlapCycles = perf.dataflowOverlapCycles.litValue.toInt
        val bubbleCycles = perf.bubbleCycles.litValue.toInt

        println("\n" + "=" * 70)
        println("DMA-Compute Overlap 性能测试（3 个 tile 流水线）")
        println("=" * 70)
        println(f"总周期数:           $totalCycles%5d")
        println(f"Cube 计算周期:      $cubeComputeCycles%5d (3 次 MATMUL)")
        println(f"MTE2 DMA 周期:      $dmaTotalCycles%5d")
        println(f"CopyIn 周期:        $copyInCycles%5d")
        println(f"CopyOut 周期:       $copyOutCycles%5d")
        println(f"MTE/Cube 重叠周期:  $overlapCycles%5d ★")
        println(f"数据流重叠周期:     $dataflowOverlapCycles%5d")
        println(f"气泡周期:           $bubbleCycles%5d")
        println(f"重叠率:             ${if (dmaTotalCycles > 0) overlapCycles * 100.0 / dmaTotalCycles
          else 0.0}%.1f%%")
        println(f"计算效率:           ${cubeComputeCycles * 100.0 / totalCycles}%.1f%%")
        println("=" * 70)
        println("说明：")
        println("  - 重叠周期 > 0 表示 DMA 与 Compute 成功并行执行")
        println("  - 理想情况下，tile 1 和 tile 2 的 DMA 应完全隐藏在前一 tile 的计算中")
        println("=" * 70)

        // 断言：应该有显著的重叠
        assert(overlapCycles > 0, "Expected overlap cycles > 0, but got 0. Overlap not working!")
        assert(
          overlapCycles > cubeComputeCycles / 2,
          s"Expected significant overlap (> ${cubeComputeCycles / 2}), but got $overlapCycles"
        )
      }
    }
  }
}
