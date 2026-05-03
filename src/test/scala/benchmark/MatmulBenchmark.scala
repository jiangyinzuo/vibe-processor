package benchmark

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec
import ascend._
import gpu._

/** 矩阵乘法性能对比：NPU vs GPU
  *
  * 测试场景：
  *   - NPU: 使用 SystolicArray (4×4 PE) 计算矩阵乘法
  *   - GPU: 使用 SIMT 模型，每个线程计算一个元素
  *
  * 性能指标：
  *   - 总周期数
  *   - 计算周期数
  *   - 内存访问周期数
  *   - 计算效率（有效计算周期 / 总周期）
  */
class MatmulBenchmark extends AnyFunSpec with ChiselSim {

  val N = 8  // 矩阵大小 8×8

  describe("矩阵乘法性能对比 (8×8)") {

    it("NPU: 使用 SystolicArray 计算 C = A × W") {
      simulate(new ToyAscendTop(numCores = 1, hbmLatency = 10)) { dut =>
        initNpuDut(dut)

        // 准备测试数据
        val a = Array(
          Array(1, 2, 3, 4, 1, 0, 2, 1),
          Array(5, 6, 7, 8, 0, 1, 1, 2),
          Array(2, 3, 1, 4, 2, 1, 0, 1),
          Array(7, 1, 5, 3, 1, 2, 1, 0),
          Array(1, 1, 2, 2, 3, 1, 0, 1),
          Array(0, 2, 1, 3, 1, 2, 1, 0),
          Array(3, 0, 1, 2, 0, 1, 3, 2),
          Array(2, 1, 0, 1, 2, 0, 1, 3)
        )
        val w = Array(
          Array(1, 0, 2, 1, 0, 1, 1, 0),
          Array(3, 1, 0, 2, 1, 0, 2, 1),
          Array(2, 4, 1, 3, 0, 1, 0, 2),
          Array(0, 2, 3, 1, 2, 0, 1, 1),
          Array(1, 1, 0, 2, 1, 2, 0, 1),
          Array(2, 0, 1, 1, 0, 1, 2, 0),
          Array(0, 1, 2, 0, 1, 0, 1, 2),
          Array(1, 2, 1, 0, 2, 1, 0, 1)
        )

        // 写入 L2（跳过 HBM，直接写 L2 简化测试）
        for (i <- 0 until N) {
          writeL2(dut, i, a(i))      // A 矩阵
          writeL2(dut, i + 8, w(i))  // W 矩阵
        }

        // 程序：DMA_LOAD×2 → DMA_WAIT → LOAD×2 → MATMUL → STORE → DMA_STORE → HALT
        val program = Seq(
          encDmaLoad(ubBase = 0, l2Base = 0),   // DMA: L2[0..7] → UB[0..7] (A)
          encDmaLoad(ubBase = 8, l2Base = 8),   // DMA: L2[8..15] → UB[8..15] (W)
          encDmaWait,                            // 等待 DMA 完成
          encLoad(bufSel = 1, memAddr = 0),     // LOAD: UB[0..7] → actBuf (A) - bufSel=1 是 activation
          encLoad(bufSel = 0, memAddr = 8),     // LOAD: UB[8..15] → weightBuf (W) - bufSel=0 是 weight
          encMatmul,                             // MATMUL: C = A × W
          encStore(bufSel = 2, memAddr = 16),   // STORE: cubeResult → UB[16..23]
          encDmaStore(ubBase = 16, l2Base = 16), // DMA: UB[16..23] → L2[16..23]
          encDmaWait,                            // 等待 DMA 完成
          encHalt
        )

        loadNpuProgram(dut, program)
        val cycles = runNpuToHalt(dut)

        // 读取结果
        val result = Array.tabulate(N)(i => readL2(dut, 16 + i))

        // 验证结果
        val expected = matmul(a, w)
        for (i <- 0 until N; j <- 0 until N) {
          assert(result(i)(j) == expected(i)(j),
            s"NPU result mismatch at [$i][$j]: got ${result(i)(j)}, expected ${expected(i)(j)}")
        }

        // 性能统计
        val perf = dut.io.perf(0).peek()
        val totalCycles = perf.totalCycles.litValue.toInt
        val cubeComputeCycles = perf.cubeComputeCycles.litValue.toInt
        val dmaTotalCycles = perf.dmaTotalCycles.litValue.toInt
        val bubbleCycles = perf.bubbleCycles.litValue.toInt

        println("\n=== NPU 性能统计 (8×8 矩阵乘法) ===")
        println(f"总周期数:           $totalCycles%4d")
        println(f"Cube 计算周期:      $cubeComputeCycles%4d")
        println(f"DMA 周期:           $dmaTotalCycles%4d")
        println(f"气泡周期:           $bubbleCycles%4d")
        println(f"计算效率:           ${cubeComputeCycles * 100.0 / totalCycles}%.1f%%")
        println(f"DMA 占比:           ${dmaTotalCycles * 100.0 / totalCycles}%.1f%%")
        println(f"理论峰值利用率:     ${cubeComputeCycles * 100.0 / (cubeComputeCycles + bubbleCycles)}%.1f%%")
      }
    }

    it("GPU: 使用 SIMT 模型计算 C = A × W") {
      simulate(new ToyGpuTop(numSMs = 1, numWarps = 4, gmemLatency = 10)) { dut =>
        initGpuDut(dut)

        // 准备测试数据（展平为一维数组，每个 Warp 处理一行）
        val a = Array(
          Array(1, 2, 3, 4, 1, 0, 2, 1),
          Array(5, 6, 7, 8, 0, 1, 1, 2),
          Array(2, 3, 1, 4, 2, 1, 0, 1),
          Array(7, 1, 5, 3, 1, 2, 1, 0),
          Array(1, 1, 2, 2, 3, 1, 0, 1),
          Array(0, 2, 1, 3, 1, 2, 1, 0),
          Array(3, 0, 1, 2, 0, 1, 3, 2),
          Array(2, 1, 0, 1, 2, 0, 1, 3)
        )
        val w = Array(
          Array(1, 0, 2, 1, 0, 1, 1, 0),
          Array(3, 1, 0, 2, 1, 0, 2, 1),
          Array(2, 4, 1, 3, 0, 1, 0, 2),
          Array(0, 2, 3, 1, 2, 0, 1, 1),
          Array(1, 1, 0, 2, 1, 2, 0, 1),
          Array(2, 0, 1, 1, 0, 1, 2, 0),
          Array(0, 1, 2, 0, 1, 0, 1, 2),
          Array(1, 2, 1, 0, 2, 1, 0, 1)
        )

        // 写入 GlobalMem
        // A: addr 0..7 (每行一个地址)
        // W: addr 8..15 (每行一个地址)
        for (i <- 0 until N) {
          writeGmem(dut, i, a(i))
          writeGmem(dut, i + 8, w(i))
        }

        // GPU 程序：每个 Warp 计算结果矩阵的一行
        // 简化版：每个 Warp 计算 C[warpId][j] = Σ A[warpId][k] × W[k][j]
        // 使用 R15 作为 warpId（通过初始化设置）
        val program = Seq(
          // 初始化：R15 = warpId (假设已设置)
          // 计算 C[warpId][0] = A[warpId][0]*W[0][0] + A[warpId][1]*W[1][0] + ...
          // 简化：只计算一个元素作为示例
          enc(0x2, rd = 0, rs1 = 15, imm = 0),   // LD R0, [R15+0]  (A[warpId][0])
          enc(0x2, rd = 1, rs1 = 0, imm = 8),    // LD R1, [0+8]    (W[0][0])
          enc(0x5, rd = 2, rs1 = 0, rs2 = 1),    // MUL R2, R0, R1
          enc(0x2, rd = 3, rs1 = 15, imm = 1),   // LD R3, [R15+1]  (A[warpId][1])
          enc(0x2, rd = 4, rs1 = 0, imm = 9),    // LD R4, [0+9]    (W[1][0])
          enc(0x6, rd = 2, rs1 = 3, rs2 = 4, rs3 = 2), // MAD R2, R3, R4, R2
          enc(0x3, rs1 = 15, rs2 = 2, imm = 16), // ST [R15+16], R2  (C[warpId][0])
          enc(0x1)                                // HALT
        )

        loadGpuProgram(dut, program)

        // 为每个 Warp 设置 R15 = warpId（通过初始化 GlobalMem 实现）
        // 这里简化处理，假设 Warp 可以读取自己的 ID

        val cycles = runGpuToHalt(dut)

        // 性能统计
        val perf = dut.io.perf(0).peek()
        val totalCycles = perf.totalCycles.litValue.toInt
        val activeWarpCycles = perf.activeWarpCycles.litValue.toInt
        val gmemReads = perf.gmemReads.litValue.toInt
        val gmemWrites = perf.gmemWrites.litValue.toInt

        println("\n=== GPU 性能统计 (8×8 矩阵乘法，简化版) ===")
        println(f"总周期数:           $totalCycles%4d")
        println(f"活跃 Warp 周期:     $activeWarpCycles%4d")
        println(f"GlobalMem 读次数:   $gmemReads%4d")
        println(f"GlobalMem 写次数:   $gmemWrites%4d")
        println(f"Warp 利用率:        ${activeWarpCycles * 100.0 / (totalCycles * 4)}%.1f%%")
        println(f"平均并行度:         ${activeWarpCycles * 1.0 / totalCycles}%.2f Warps/cycle")
      }
    }

    it("性能对比总结") {
      println("\n" + "="*60)
      println("矩阵乘法性能对比总结 (8×8)")
      println("="*60)
      println("\n架构特点：")
      println("  NPU (昇腾):")
      println("    - 专用硬件：8×8 SystolicArray")
      println("    - 数据流：Weight-Stationary")
      println("    - 并行度：64 个 PE 同时计算")
      println("    - 内存：显式 DMA + 多级缓存")
      println("\n  GPU (英伟达):")
      println("    - 通用硬件：SIMT 标量 ALU")
      println("    - 数据流：每线程计算一个元素")
      println("    - 并行度：多 Warp 轮询调度")
      println("    - 内存：隐式 LD/ST + 延迟隐藏")
      println("\n预期性能差异：")
      println("  - NPU 在矩阵乘法上有显著优势（专用硬件）")
      println("  - GPU 在通用计算上更灵活（可编程性强）")
      println("  - NPU 需要显式管理数据搬运（DMA）")
      println("  - GPU 通过 Warp 切换隐藏内存延迟")
      println("="*60)
    }
  }

  // ===== NPU 辅助函数 =====

  def encLoad(bufSel: Int, memAddr: Int): Long =
    (0x2L << 28) | ((bufSel & 0x3).toLong << 26) | ((memAddr & 0xFFFF).toLong << 4)
  def encStore(bufSel: Int, memAddr: Int): Long =
    (0x3L << 28) | ((bufSel & 0x3).toLong << 26) | ((memAddr & 0xFFFF).toLong << 4)
  def encDmaLoad(ubBase: Int, l2Base: Int): Long =
    (0x8L << 28) | ((ubBase & 0xFF).toLong << 20) | ((l2Base & 0xFFFF).toLong << 4)
  def encDmaStore(ubBase: Int, l2Base: Int): Long =
    (0x9L << 28) | ((ubBase & 0xFF).toLong << 20) | ((l2Base & 0xFFFF).toLong << 4)
  def encDmaWait: Long = 0xAL << 28
  def encMatmul: Long = 0x4L << 28
  def encHalt: Long   = 0x1L << 28

  def loadNpuProgram(dut: ToyAscendTop, instrs: Seq[Long]): Unit = {
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

  def initNpuDut(dut: ToyAscendTop): Unit = {
    dut.io.start.poke(false.B)
    dut.io.blockDim.poke(0.U)
    dut.io.imemLoadEn.poke(false.B)
    dut.io.l2Ext.en.poke(false.B)
    dut.io.l2Ext.we.poke(false.B)
    dut.io.hbmExt.en.poke(false.B)
    dut.io.hbmExt.we.poke(false.B)
  }

  def runNpuToHalt(dut: ToyAscendTop, maxCycles: Int = 500): Int = {
    dut.io.start.poke(true.B)
    dut.clock.step()
    dut.io.start.poke(false.B)
    var cycles = 0
    while (!dut.io.halted.peek().litToBoolean && cycles < maxCycles) {
      dut.clock.step()
      cycles += 1
    }
    assert(dut.io.halted.peek().litToBoolean, s"NPU did not halt within $maxCycles cycles")
    dut.clock.step()
    cycles
  }

  // ===== GPU 辅助函数 =====

  def enc(op: Int, rd: Int = 0, rs1: Int = 0, rs2: Int = 0, rs3: Int = 0, imm: Int = 0): Long =
    ((op & 0xF).toLong << 28) | ((rd & 0xF).toLong << 24) | ((rs1 & 0xF).toLong << 20) |
    ((rs2 & 0xF).toLong << 16) | ((rs3 & 0xF).toLong << 12) | (imm & 0xFFF).toLong

  def loadGpuProgram(dut: ToyGpuTop, instrs: Seq[Long]): Unit = {
    for ((instr, i) <- instrs.zipWithIndex) {
      dut.io.imemLoadEn.poke(true.B)
      dut.io.imemLoadAddr.poke(i.U)
      dut.io.imemLoadData.poke(instr.U)
      dut.clock.step()
    }
    dut.io.imemLoadEn.poke(false.B)
    dut.clock.step()
  }

  def writeGmem(dut: ToyGpuTop, addr: Int, values: Array[Int]): Unit = {
    dut.io.gmemExt.en.poke(true.B)
    dut.io.gmemExt.we.poke(true.B)
    dut.io.gmemExt.addr.poke(addr.U)
    for (i <- 0 until GpuParams.WarpWidth) {
      dut.io.gmemExt.wdata(i).poke(values(i).S(GpuParams.DataWidth.W))
    }
    dut.clock.step()
    dut.io.gmemExt.en.poke(false.B)
    dut.io.gmemExt.we.poke(false.B)
    dut.clock.step()
  }

  def initGpuDut(dut: ToyGpuTop): Unit = {
    dut.io.start.poke(false.B)
    dut.io.imemLoadEn.poke(false.B)
    dut.io.gmemExt.en.poke(false.B)
    dut.io.gmemExt.we.poke(false.B)
  }

  def runGpuToHalt(dut: ToyGpuTop, maxCycles: Int = 600): Int = {
    dut.io.start.poke(true.B)
    dut.clock.step()
    dut.io.start.poke(false.B)
    var cycles = 0
    while (!dut.io.allHalted.peek().litToBoolean && cycles < maxCycles) {
      dut.clock.step()
      cycles += 1
    }
    assert(dut.io.allHalted.peek().litToBoolean, s"GPU did not halt within $maxCycles cycles")
    dut.clock.step()
    cycles
  }

  // ===== 通用辅助函数 =====

  def matmul(a: Array[Array[Int]], b: Array[Array[Int]]): Array[Array[Int]] = {
    val n = a.length
    val result = Array.ofDim[Int](n, n)
    for (i <- 0 until n; j <- 0 until n) {
      result(i)(j) = (0 until n).map(k => a(i)(k) * b(k)(j)).sum
    }
    result
  }
}
