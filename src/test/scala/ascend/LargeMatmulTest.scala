package ascend

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec

/** 大矩阵乘法性能测试：使用当前 16×16 Cube tile，并估算更大矩阵的 tiling 成本。
  *
  * 测试场景：
  *   - 16×16 矩阵乘法，一个 Cube tile 完成
  *   - 32×32 矩阵乘法的 16×16 tile 分解估算
  *   - 展示 NPU 在大矩阵上的性能优势
  */
class LargeMatmulTest extends AnyFunSpec with ChiselSim {

  val N = AscendParams.ArraySize // 16×16 tile size

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
  def encVecadd: Long = 0x5L << 28
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

  def matmulTile(a: Array[Array[Int]], w: Array[Array[Int]]): Array[Array[Int]] = {
    val result = Array.ofDim[Int](N, N)
    for (i <- 0 until N; j <- 0 until N) {
      result(i)(j) = (0 until N).map(k => a(i)(k) * w(k)(j)).sum
    }
    result
  }

  describe("大矩阵乘法性能测试") {

    it("16×16 矩阵乘法（单程序完成）") {
      simulate(new ToyAscendTop(numCores = 1, hbmLatency = 10)) { dut =>
        initDut(dut)

        // 简单的 16×16 矩阵乘法测试
        val a = Array.tabulate(N, N)((i, j) => (i + j + 1) % 8)
        val w = Array.tabulate(N, N)((i, j) => (i * 2 + j) % 8)

        // 写入 L2
        for (i <- 0 until N) {
          writeL2(dut, i, a(i))
          writeL2(dut, i + N, w(i))
        }

        // 单个矩阵乘法
        val program = Seq(
          encDmaLoad(ubBase = 0, l2Base = 0),
          encDmaLoad(ubBase = N, l2Base = N),
          encDmaWait,
          encLoad(bufSel = 1, memAddr = 0),
          encLoad(bufSel = 0, memAddr = N),
          encMatmul,
          encStore(bufSel = 2, memAddr = 2 * N),
          encDmaStore(ubBase = 2 * N, l2Base = 2 * N),
          encDmaWait,
          encHalt
        )

        loadProgram(dut, program)
        val totalCycles = runToHalt(dut)

        // 读取结果
        val result = Array.tabulate(N)(i => readL2(dut, 2 * N + i))

        // 验证结果
        val expected = matmulTile(a, w)

        for (i <- 0 until N; j <- 0 until N) {
          assert(
            result(i)(j) == expected(i)(j),
            s"Mismatch at [$i][$j]: got ${result(i)(j)}, expected ${expected(i)(j)}"
          )
        }

        println("\n" + "=" * 60)
        println("16×16 矩阵乘法性能测试")
        println("=" * 60)
        println(f"总周期数:                 $totalCycles%4d")
        println("=" * 60)
      }
    }

    it("32×32 矩阵乘法性能估算") {
      println("\n" + "=" * 60)
      println("32×32 矩阵乘法性能估算")
      println("=" * 60)
      println("\n基于 16×16 SystolicArray 的 Tiling 分析：")
      println("  - 32×32 矩阵分解为 2×2 个 16×16 输出块")
      println("  - 每个输出块需要 2 次 MATMUL_ACC 覆盖 K 方向两个 tile")
      println("  - 总共需要：2×2 × 2 = 8 次 MATMUL/MATMUL_ACC")
      println("\n假设每个 16×16 tile 约 349 周期（当前单 tile 集成测试）：")
      println("  - 粗略上限: 8 × 349 = 2792 周期")
      println("  - 若 DMA 与 Cube 重叠，实际可低于该顺序上限")
      println("\n理论峰值性能：")
      println("  - 32×32×32 = 32,768 次乘加操作")
      println("  - 16×16 SystolicArray 峰值为 256 个 PE")
      println("  - 不考虑填充/流水/搬运时，理论最少约 128 个 PE 周期")
      println("\n瓶颈分析：")
      println("  ✗ DMA 开销仍大：每个 K tile 都要搬运 A/B tile")
      println("  ✗ Tiling 需要 L0C 累加和最终 STORE")
      println("\n优化方向：")
      println("  ✓ 使用更真实的分形格式搬运，减少实时重排")
      println("  ✓ 使用更大的片上缓存，减少 DMA 次数")
      println("  ✓ 流水线优化：DMA 和计算重叠（已实现基础设施）")
      println("  ✓ 多核并行：2 核可以并行处理不同的输出块")
      println("=" * 60)
    }
  }
}
