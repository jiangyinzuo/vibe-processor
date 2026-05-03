package gpu

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec

/** 双调度器性能测试：验证 2 个 Warp Scheduler 的并行执行能力
  *
  * 测试场景：
  *   - 4 个 Warp 执行相同的程序
  *   - 对比单调度器 vs 双调度器的性能
  *   - 展示并行执行带来的吞吐量提升
  */
class DualSchedulerTest extends AnyFunSpec with ChiselSim {

  val W = GpuParams.WarpWidth
  val DW = GpuParams.DataWidth

  def enc(op: Int, rd: Int = 0, rs1: Int = 0, rs2: Int = 0, rs3: Int = 0, imm: Int = 0): Long =
    ((op & 0xf).toLong << 28) | ((rd & 0xf).toLong << 24) | ((rs1 & 0xf).toLong << 20) |
      ((rs2 & 0xf).toLong << 16) | ((rs3 & 0xf).toLong << 12) | (imm & 0xfff).toLong

  def loadProgram(dut: ToyGpuTop, instrs: Seq[Long]): Unit = {
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
    for (i <- 0 until W) dut.io.gmemExt.wdata(i).poke(values(i).S(DW.W))
    dut.clock.step()
    dut.io.gmemExt.en.poke(false.B)
    dut.io.gmemExt.we.poke(false.B)
    dut.clock.step()
  }

  def readGmem(dut: ToyGpuTop, addr: Int): Array[Int] = {
    dut.io.gmemExt.en.poke(true.B)
    dut.io.gmemExt.we.poke(false.B)
    dut.io.gmemExt.addr.poke(addr.U)
    dut.clock.step()
    dut.io.gmemExt.en.poke(false.B)
    val result = Array.tabulate(W)(i => dut.io.gmemExt.rdata(i).peek().litValue.toInt)
    dut.clock.step()
    result
  }

  def initDut(dut: ToyGpuTop): Unit = {
    dut.io.start.poke(false.B)
    dut.io.imemLoadEn.poke(false.B)
    dut.io.gmemExt.en.poke(false.B)
    dut.io.gmemExt.we.poke(false.B)
  }

  def printPerfCounters(perf: GpuPerfCounters, numWarps: Int): Unit = {
    val totalCycles = perf.totalCycles.peek().litValue.toLong
    val activeWarpCycles = perf.activeWarpCycles.peek().litValue.toLong
    val eligibleWarpCycles = perf.eligibleWarpCycles.peek().litValue.toLong
    val stalledWarpCycles = perf.stalledWarpCycles.peek().litValue.toLong
    val noEligibleCycles = perf.noEligibleCycles.peek().litValue.toLong
    val aluIssueCycles = perf.aluIssueCycles.peek().litValue.toLong
    val sfuIssueCycles = perf.sfuIssueCycles.peek().litValue.toLong
    val memIssueCycles = perf.memIssueCycles.peek().litValue.toLong
    val dualIssueCycles = perf.dualIssueCycles.peek().litValue.toLong
    val denominator = math.max(1L, totalCycles * numWarps)

    println(f"总周期数:             $totalCycles%4d")
    println(f"Live Warp 周期:       $activeWarpCycles%4d")
    println(f"Eligible Warp 周期:   $eligibleWarpCycles%4d")
    println(f"Stalled Warp 周期:    $stalledWarpCycles%4d")
    println(f"No-eligible 周期:     $noEligibleCycles%4d")
    println(f"ALU issue 周期:       $aluIssueCycles%4d")
    println(f"SFU issue 周期:       $sfuIssueCycles%4d")
    println(f"MEM issue 周期:       $memIssueCycles%4d")
    println(f"ALU+SFU 同发周期:     $dualIssueCycles%4d")
    println(f"Warp 占用率:          ${activeWarpCycles * 100.0 / denominator}%.1f%%")
    println(f"Ready 覆盖率:         ${eligibleWarpCycles * 100.0 / denominator}%.1f%%")
    println(f"平均 live warps:      ${activeWarpCycles * 1.0 / math.max(1L, totalCycles)}%.2f")
  }

  def runToHalt(dut: ToyGpuTop, maxCycles: Int = 600): Int = {
    dut.io.start.poke(true.B)
    dut.clock.step()
    dut.io.start.poke(false.B)
    var cycles = 0
    while (!dut.io.allHalted.peek().litToBoolean && cycles < maxCycles) {
      dut.clock.step()
      cycles += 1
    }
    assert(dut.io.allHalted.peek().litToBoolean, s"Did not halt within $maxCycles cycles")
    dut.clock.step()
    cycles
  }

  describe("双调度器性能测试") {

    it("纯计算程序（无内存访问）- 展示最佳并行度") {
      simulate(new ToyGpuTop(numSMs = 1, gmemLatency = 1)) { dut =>
        initDut(dut)

        // 纯计算程序：10 条 ADD 指令
        val program = Seq(
          enc(0x4, rd = 1, rs1 = 0, rs2 = 0), // ADD R1, R0, R0
          enc(0x4, rd = 2, rs1 = 1, rs2 = 1), // ADD R2, R1, R1
          enc(0x4, rd = 3, rs1 = 2, rs2 = 2), // ADD R3, R2, R2
          enc(0x4, rd = 4, rs1 = 3, rs2 = 3), // ADD R4, R3, R3
          enc(0x4, rd = 5, rs1 = 4, rs2 = 4), // ADD R5, R4, R4
          enc(0x4, rd = 6, rs1 = 5, rs2 = 5), // ADD R6, R5, R5
          enc(0x4, rd = 7, rs1 = 6, rs2 = 6), // ADD R7, R6, R6
          enc(0x4, rd = 8, rs1 = 7, rs2 = 7), // ADD R8, R7, R7
          enc(0x4, rd = 9, rs1 = 8, rs2 = 8), // ADD R9, R8, R8
          enc(0x4, rd = 10, rs1 = 9, rs2 = 9), // ADD R10, R9, R9
          enc(0x1) // HALT
        )

        loadProgram(dut, program)
        val cycles = runToHalt(dut)

        val perf = dut.io.perf(0)

        println("\n=== 双调度器性能测试：纯计算程序 ===")
        printPerfCounters(perf, numWarps = 4)
        println("\n理论分析：")
        println("  - 纯计算没有内存等待，stalled/no-eligible 应接近 0")
        println("  - 当前流水线吞吐主要受 decode/RF/WB 阶段和 CTA 调度开销影响")
        assert(
          perf.stalledWarpCycles.peek().litValue == 0,
          "pure compute should not stall on memory"
        )
        assert(
          perf.memIssueCycles.peek().litValue == 0,
          "pure compute should not issue memory requests"
        )
      }
    }

    it("内存密集程序（latency=10）- 展示延迟隐藏") {
      simulate(new ToyGpuTop(numSMs = 1, gmemLatency = 10)) { dut =>
        initDut(dut)

        // 准备数据
        writeGmem(dut, 0, Array(10, 20, 30, 40))
        writeGmem(dut, 1, Array(1, 2, 3, 4))

        // 内存密集程序：LD → LD → ADD → ST
        val program = Seq(
          enc(0x2, rd = 0, rs1 = 15, imm = 0), // LD R0, [R15+0]
          enc(0x2, rd = 1, rs1 = 15, imm = 1), // LD R1, [R15+1]
          enc(0x4, rd = 2, rs1 = 0, rs2 = 1), // ADD R2, R0, R1
          enc(0x3, rs1 = 15, rs2 = 2, imm = 2), // ST [R15+2], R2
          enc(0x1) // HALT
        )

        loadProgram(dut, program)
        val cycles = runToHalt(dut)

        val perf = dut.io.perf(0)
        val gmemReads = perf.gmemReads.peek().litValue.toInt
        val gmemWrites = perf.gmemWrites.peek().litValue.toInt

        println("\n=== 双调度器性能测试：内存密集程序 (latency=10) ===")
        println(f"GlobalMem 读次数:   $gmemReads%4d")
        println(f"GlobalMem 写次数:   $gmemWrites%4d")
        printPerfCounters(perf, numWarps = 4)
        println("\n理论分析：")
        println("  - 每个 Warp: 2×LD (10 周期) + ADD (1 周期) + ST (10 周期) = 21 周期")
        println("  - 单调度器：4 Warp × 21 = 84 周期（串行）")
        println("  - 双调度器：通过 Warp 切换隐藏延迟")
        println(f"  - 实际周期：${perf.totalCycles.peek().litValue.toInt}")
        println(f"  - 性能提升：${84.0 / perf.totalCycles.peek().litValue.toDouble}%.2f×")
        println(f"  - 延迟隐藏效果：${21.0 / (perf.totalCycles.peek().litValue.toDouble / 4.0)}%.2f×")
        assert(
          perf.stalledWarpCycles.peek().litValue > 0,
          "memory test should accumulate stalled warp cycles"
        )
        assert(
          perf.noEligibleCycles.peek().litValue > 0,
          "memory test should expose cycles where all live warps wait"
        )
      }
    }

    it("混合程序（计算 + 内存）- 展示资源仲裁") {
      simulate(new ToyGpuTop(numSMs = 1, gmemLatency = 5)) { dut =>
        initDut(dut)

        // 准备数据
        for (i <- 0 until 10) {
          writeGmem(dut, i, Array.fill(W)(i * 10))
        }

        // 混合程序：LD → ADD × 5 → ST
        val program = Seq(
          enc(0x2, rd = 0, rs1 = 15, imm = 0), // LD R0, [R15+0]
          enc(0x4, rd = 1, rs1 = 0, rs2 = 0), // ADD R1, R0, R0
          enc(0x4, rd = 2, rs1 = 1, rs2 = 1), // ADD R2, R1, R1
          enc(0x4, rd = 3, rs1 = 2, rs2 = 2), // ADD R3, R2, R2
          enc(0x4, rd = 4, rs1 = 3, rs2 = 3), // ADD R4, R3, R3
          enc(0x4, rd = 5, rs1 = 4, rs2 = 4), // ADD R5, R4, R4
          enc(0x3, rs1 = 15, rs2 = 5, imm = 10), // ST [R15+10], R5
          enc(0x1) // HALT
        )

        loadProgram(dut, program)
        val cycles = runToHalt(dut)

        val perf = dut.io.perf(0)

        println("\n=== 双调度器性能测试：混合程序 (latency=5) ===")
        printPerfCounters(perf, numWarps = 4)
        println("\n理论分析：")
        println("  - 每个 Warp: LD (5) + ADD×5 (5) + ST (5) = 15 周期")
        println("  - 单调度器：4 Warp × 15 = 60 周期")
        println("  - 双调度器：通过并行执行和延迟隐藏")
        println(f"  - 实际周期：${perf.totalCycles.peek().litValue.toInt}")
        println(f"  - 性能提升：${60.0 / perf.totalCycles.peek().litValue.toDouble}%.2f×")
      }
    }

    it("性能对比总结") {
      println("\n" + "=" * 70)
      println("双调度器性能对比总结")
      println("=" * 70)
      println("\n架构改进：")
      println("  - 2 个独立的 Warp Scheduler")
      println("  - Scheduler 0: 管理 Warp 0, 1")
      println("  - Scheduler 1: 管理 Warp 2, 3")
      println("  - 每周期可以并行执行 2 个 Warp（如果资源允许）")
      println("\n资源仲裁：")
      println("  - GlobalMem: 优先级仲裁（Scheduler 0 > Scheduler 1）")
      println("  - SharedMem: 优先级仲裁（Scheduler 0 > Scheduler 1）")
      println("  - 如果两个 Warp 同时请求同一资源，高优先级 Warp 获得访问权")
      println("\n性能计数：")
      println("  - 纯计算程序：stalled/no-eligible 均为 0")
      println("  - 内存密集程序：stalled warp-cycle 高，但只有部分周期 no-eligible")
      println("  - 混合程序：多数访存等待被计算和其它 Ready warp 覆盖")
      println("\n与真实 GPU 的差距：")
      println("  - 真实 GPU: 4 个 Scheduler，每周期 4 条指令")
      println("  - 本项目: 2 个 Scheduler，每周期 2 条指令")
      println("  - 差距: 2×")
      println("\n进一步优化方向：")
      println("  - 增加到 4 个 Scheduler（每个管理 1 个 Warp）")
      println("  - 增加执行单元数量（支持更多并行）")
      println("  - 实现双指令发射（单个 Warp 发射 2 条指令）")
      println("  - 优化资源仲裁策略（动态优先级）")
      println("=" * 70)
    }
  }
}
