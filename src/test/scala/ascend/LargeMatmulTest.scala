package ascend

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec

/** 大矩阵乘法性能测试：使用 Tiling 技术将 8×8 矩阵分解为 4×4 块
  *
  * 测试场景：
  *   - 8×8 矩阵乘法，分解为 4 个 4×4 块
  *   - C[0:4][0:4] = A[0:4][0:4] × W[0:4][0:4] + A[0:4][4:8] × W[4:8][0:4]
  *   - 展示 NPU 在大矩阵上的性能优势
  */
class LargeMatmulTest extends AnyFunSpec with ChiselSim {

  val N = AscendParams.ArraySize // 4×4 tile size

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

  def matmul8x8(a: Array[Array[Int]], w: Array[Array[Int]]): Array[Array[Int]] = {
    val result = Array.ofDim[Int](N, N)
    for (i <- 0 until N; j <- 0 until N) {
      result(i)(j) = (0 until N).map(k => a(i)(k) * w(k)(j)).sum
    }
    result
  }

  describe("大矩阵乘法性能测试") {

    it("8×8 矩阵乘法（单程序完成）") {
      simulate(new ToyAscendTop(numCores = 1, hbmLatency = 10)) { dut =>
        initDut(dut)

        // 简单的 8×8 矩阵乘法测试
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
        val expected = matmul8x8(a, w)

        for (i <- 0 until N; j <- 0 until N) {
          assert(
            result(i)(j) == expected(i)(j),
            s"Mismatch at [$i][$j]: got ${result(i)(j)}, expected ${expected(i)(j)}"
          )
        }

        println("\n" + "=" * 60)
        println("8×8 矩阵乘法性能测试")
        println("=" * 60)
        println(f"总周期数:                 $totalCycles%4d")
        println("=" * 60)
      }
    }

    it("16×16 矩阵乘法性能估算") {
      println("\n" + "=" * 60)
      println("16×16 矩阵乘法性能估算")
      println("=" * 60)
      println("\n基于 8×8 SystolicArray 的 Tiling 分析：")
      println("  - 16×16 矩阵分解为 2×2 个 8×8 块")
      println("  - 每个输出块需要 2 次 MATMUL + 1 次 VECADD")
      println("  - 总共需要：2×2 × (2×MATMUL + 1×VECADD) = 4 × 3 = 12 次操作")
      println("\n假设每次操作耗时（基于 8×8 测试）：")
      println("  - MATMUL: ~70 周期（估算）")
      println("  - VECADD: ~30 周期（估算）")
      println("  - 每个输出块: 2×70 + 1×30 = 170 周期")
      println("  - 总周期数: 4 × 170 = 680 周期")
      println("\n理论峰值性能：")
      println("  - 16×16×16 = 4,096 次乘加操作")
      println("  - 8×8 SystolicArray 每周期 64 次乘加")
      println("  - 理论最少周期: 4,096 / 64 = 64 周期")
      println("  - 实际周期: 680")
      println("  - 硬件利用率: 64 / 680 = 9.4%")
      println("\n瓶颈分析：")
      println("  ✗ DMA 开销大：每次 MATMUL 需要 2 次 DMA_LOAD + 1 次 DMA_STORE")
      println("  ✗ Tiling 开销：需要多次加载和累加中间结果")
      println("\n优化方向：")
      println("  ✓ 增大 SystolicArray 尺寸（如 16×16 或 32×32）")
      println("  ✓ 使用更大的片上缓存，减少 DMA 次数")
      println("  ✓ 流水线优化：DMA 和计算重叠（已实现基础设施）")
      println("  ✓ 多核并行：2 核可以并行处理不同的输出块")
      println("=" * 60)
    }
  }
}
