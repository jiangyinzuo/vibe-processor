package benchmark

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec
import ascend._

class MatmulDebug extends AnyFunSpec with ChiselSim {

  val N = 8

  describe("调试 8×8 矩阵乘法") {

    it("简单的 2×2 子矩阵测试") {
      simulate(new ToyAscendTop(numCores = 1, hbmLatency = 10)) { dut =>
        initNpuDut(dut)

        // 简单的 2×2 测试数据（填充到 8×8）
        val a = Array.tabulate(N, N)((i, j) => if (i < 2 && j < 2) i + j + 1 else 0)
        val w = Array.tabulate(N, N)((i, j) => if (i < 2 && j < 2) 1 else 0)

        println("Input A:")
        for (i <- 0 until N) println(a(i).mkString(", "))
        println("\nInput W:")
        for (i <- 0 until N) println(w(i).mkString(", "))

        // 写入 L2
        for (i <- 0 until N) {
          writeL2(dut, i, a(i))
          writeL2(dut, i + 8, w(i))
        }

        // 程序
        val program = Seq(
          encDmaLoad(ubBase = 0, l2Base = 0),
          encDmaLoad(ubBase = 8, l2Base = 8),
          encDmaWait,
          encLoad(bufSel = 1, memAddr = 0), // bufSel=1 是 activation
          encLoad(bufSel = 0, memAddr = 8), // bufSel=0 是 weight
          encMatmul,
          encStore(bufSel = 2, memAddr = 16),
          encDmaStore(ubBase = 16, l2Base = 16),
          encDmaWait,
          encHalt
        )

        loadNpuProgram(dut, program)
        runNpuToHalt(dut)

        // 读取结果
        val result = Array.tabulate(N)(i => readL2(dut, 16 + i))

        println("\nResult:")
        for (i <- 0 until N) println(result(i).mkString(", "))

        // 计算期望
        val expected = matmul(a, w)
        println("\nExpected:")
        for (i <- 0 until N) println(expected(i).mkString(", "))

        // 验证
        for (i <- 0 until N; j <- 0 until N) {
          if (result(i)(j) != expected(i)(j)) {
            println(s"Mismatch at [$i][$j]: got ${result(i)(j)}, expected ${expected(i)(j)}")
          }
        }
      }
    }
  }

  // 辅助函数
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

  def matmul(a: Array[Array[Int]], b: Array[Array[Int]]): Array[Array[Int]] = {
    val n = a.length
    val result = Array.ofDim[Int](n, n)
    for (i <- 0 until n; j <- 0 until n) {
      result(i)(j) = (0 until n).map(k => a(i)(k) * b(k)(j)).sum
    }
    result
  }
}
