package gpu

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec

class SharedArchDebug extends AnyFunSpec with ChiselSim {

  val W  = GpuParams.WarpWidth
  val DW = GpuParams.DataWidth

  def enc(op: Int, rd: Int = 0, rs1: Int = 0, rs2: Int = 0, rs3: Int = 0, imm: Int = 0): Long =
    ((op & 0xF).toLong << 28) | ((rd & 0xF).toLong << 24) | ((rs1 & 0xF).toLong << 20) |
    ((rs2 & 0xF).toLong << 16) | ((rs3 & 0xF).toLong << 12) | (imm & 0xFFF).toLong

  describe("Shared Architecture Debug") {

    it("simple ADD without memory") {
      simulate(new ToyGpuTop(gmemLatency = 1)) { dut =>
        // 初始化
        dut.io.start.poke(false.B)
        dut.io.imemLoadEn.poke(false.B)
        dut.io.gmemExt.en.poke(false.B)
        dut.clock.step()

        // 加载程序：直接在寄存器上做加法（不访问内存）
        // R0 和 R1 初始值为 0，但我们可以用 ADD 来设置它们
        val program = Seq(
          enc(0x4, rd = 0, rs1 = 0, rs2 = 0),   // ADD R0, R0, R0  (R0 = 0)
          enc(0x4, rd = 1, rs1 = 0, rs2 = 0),   // ADD R1, R0, R0  (R1 = 0)
          enc(0x4, rd = 2, rs1 = 0, rs2 = 1),   // ADD R2, R0, R1  (R2 = 0)
          enc(0x1)                                // HALT
        )

        for ((instr, i) <- program.zipWithIndex) {
          dut.io.imemLoadEn.poke(true.B)
          dut.io.imemLoadAddr.poke(i.U)
          dut.io.imemLoadData.poke(instr.U)
          dut.clock.step()
        }
        dut.io.imemLoadEn.poke(false.B)
        dut.clock.step()

        // 启动
        dut.io.start.poke(true.B)
        dut.clock.step()
        dut.io.start.poke(false.B)

        // 运行到 halt
        var cycles = 0
        while (!dut.io.allHalted.peek().litToBoolean && cycles < 100) {
          println(s"Cycle $cycles: halted=${dut.io.allHalted.peek().litToBoolean}")
          dut.clock.step()
          cycles += 1
        }

        println(s"Halted after $cycles cycles")
        assert(dut.io.allHalted.peek().litToBoolean, s"Did not halt within 100 cycles")
      }
    }

    it("single LOAD test") {
      simulate(new ToyGpuTop(gmemLatency = 1)) { dut =>
        // 初始化
        dut.io.start.poke(false.B)
        dut.io.imemLoadEn.poke(false.B)
        dut.io.gmemExt.en.poke(false.B)
        dut.clock.step()

        // 写入测试数据到 gmem[0]
        dut.io.gmemExt.en.poke(true.B)
        dut.io.gmemExt.we.poke(true.B)
        dut.io.gmemExt.addr.poke(0.U)
        for (i <- 0 until W) dut.io.gmemExt.wdata(i).poke((100 + i).S)
        dut.clock.step()

        dut.io.gmemExt.en.poke(false.B)
        dut.io.gmemExt.we.poke(false.B)
        dut.clock.step()

        // 加载程序：只做一次 LOAD
        val program = Seq(
          enc(0x2, rd = 5, rs1 = 15, imm = 0),  // LD R5, [R15+0]  (load from addr 0)
          enc(0x1)                                // HALT
        )

        for ((instr, i) <- program.zipWithIndex) {
          dut.io.imemLoadEn.poke(true.B)
          dut.io.imemLoadAddr.poke(i.U)
          dut.io.imemLoadData.poke(instr.U)
          dut.clock.step()
        }
        dut.io.imemLoadEn.poke(false.B)
        dut.clock.step()

        // 启动
        dut.io.start.poke(true.B)
        dut.clock.step()
        dut.io.start.poke(false.B)

        // 运行到 halt
        var cycles = 0
        while (!dut.io.allHalted.peek().litToBoolean && cycles < 50) {
          dut.clock.step()
          cycles += 1
        }

        println(s"Single LOAD: Halted after $cycles cycles")
        assert(dut.io.allHalted.peek().litToBoolean, s"Did not halt within 50 cycles")
      }
    }

    it("two LOADs test") {
      simulate(new ToyGpuTop(gmemLatency = 1)) { dut =>
        // 初始化
        dut.io.start.poke(false.B)
        dut.io.imemLoadEn.poke(false.B)
        dut.io.gmemExt.en.poke(false.B)
        dut.clock.step()

        // 写入测试数据
        dut.io.gmemExt.en.poke(true.B)
        dut.io.gmemExt.we.poke(true.B)
        dut.io.gmemExt.addr.poke(0.U)
        for (i <- 0 until W) dut.io.gmemExt.wdata(i).poke((10 * (i + 1)).S)
        dut.clock.step()

        dut.io.gmemExt.addr.poke(1.U)
        for (i <- 0 until W) dut.io.gmemExt.wdata(i).poke((i + 1).S)
        dut.clock.step()

        dut.io.gmemExt.en.poke(false.B)
        dut.io.gmemExt.we.poke(false.B)
        dut.clock.step()

        // 加载程序：两次 LOAD，然后 STORE R0 和 R1
        val program = Seq(
          enc(0x2, rd = 0, rs1 = 15, imm = 0),  // LD R0, [R15+0]
          enc(0x2, rd = 1, rs1 = 15, imm = 1),  // LD R1, [R15+1]
          enc(0x3, rs1 = 15, rs2 = 0, imm = 10), // ST [R15+10], R0
          enc(0x3, rs1 = 15, rs2 = 1, imm = 11), // ST [R15+11], R1
          enc(0x1)                                // HALT
        )

        for ((instr, i) <- program.zipWithIndex) {
          dut.io.imemLoadEn.poke(true.B)
          dut.io.imemLoadAddr.poke(i.U)
          dut.io.imemLoadData.poke(instr.U)
          dut.clock.step()
        }
        dut.io.imemLoadEn.poke(false.B)
        dut.clock.step()

        // 启动
        dut.io.start.poke(true.B)
        dut.clock.step()
        dut.io.start.poke(false.B)

        // 运行到 halt。流水化后 two-load 调试程序会超过旧的 50-cycle 上限。
        val maxCycles = 100
        var cycles = 0
        while (!dut.io.allHalted.peek().litToBoolean && cycles < maxCycles) {
          dut.clock.step()
          cycles += 1
        }

        println(s"Two LOADs: Halted after $cycles cycles")
        assert(dut.io.allHalted.peek().litToBoolean, s"Did not halt within $maxCycles cycles")

        // 读取 R0 的结果（存储在 gmem[10]）
        dut.io.gmemExt.en.poke(true.B)
        dut.io.gmemExt.we.poke(false.B)
        dut.io.gmemExt.addr.poke(10.U)
        dut.clock.step()
        val r0 = Array.tabulate(W)(i => dut.io.gmemExt.rdata(i).peek().litValue.toInt)
        println(s"R0 (from gmem[10]): ${r0.mkString(", ")}")
        println(s"Expected: 10, 20, 30, 40")

        // 读取 R1 的结果（存储在 gmem[11]）
        dut.io.gmemExt.addr.poke(11.U)
        dut.clock.step()
        val r1 = Array.tabulate(W)(i => dut.io.gmemExt.rdata(i).peek().litValue.toInt)
        println(s"R1 (from gmem[11]): ${r1.mkString(", ")}")
        println(s"Expected: 1, 2, 3, 4")

        dut.io.gmemExt.en.poke(false.B)

        // 验证
        for (i <- 0 until W) {
          assert(r0(i) == 10 * (i + 1), s"R0 lane $i: got ${r0(i)}, expected ${10 * (i + 1)}")
          assert(r1(i) == i + 1, s"R1 lane $i: got ${r1(i)}, expected ${i + 1}")
        }
      }
    }

    it("ADD with LOAD and STORE") {
      simulate(new ToyGpuTop(gmemLatency = 1)) { dut =>
        // 初始化
        dut.io.start.poke(false.B)
        dut.io.imemLoadEn.poke(false.B)
        dut.io.gmemExt.en.poke(false.B)
        dut.clock.step()

        // 写入测试数据到 gmem[0] 和 gmem[1]
        dut.io.gmemExt.en.poke(true.B)
        dut.io.gmemExt.we.poke(true.B)
        dut.io.gmemExt.addr.poke(0.U)
        for (i <- 0 until W) dut.io.gmemExt.wdata(i).poke((10 * (i + 1)).S)
        dut.clock.step()

        dut.io.gmemExt.addr.poke(1.U)
        for (i <- 0 until W) dut.io.gmemExt.wdata(i).poke((i + 1).S)
        dut.clock.step()

        dut.io.gmemExt.en.poke(false.B)
        dut.io.gmemExt.we.poke(false.B)
        dut.clock.step()

        // 加载程序
        val program = Seq(
          enc(0x2, rd = 0, rs1 = 15, imm = 0),  // LD R0, [R15+0]  (R15=0, so load from addr 0)
          enc(0x2, rd = 1, rs1 = 15, imm = 1),  // LD R1, [R15+1]  (load from addr 1)
          enc(0x4, rd = 2, rs1 = 0, rs2 = 1),   // ADD R2, R0, R1
          enc(0x3, rs1 = 15, rs2 = 2, imm = 2), // ST [R15+2], R2  (store to addr 2)
          enc(0x1)                                // HALT
        )

        for ((instr, i) <- program.zipWithIndex) {
          dut.io.imemLoadEn.poke(true.B)
          dut.io.imemLoadAddr.poke(i.U)
          dut.io.imemLoadData.poke(instr.U)
          dut.clock.step()
        }
        dut.io.imemLoadEn.poke(false.B)
        dut.clock.step()

        // 启动
        dut.io.start.poke(true.B)
        dut.clock.step()
        dut.io.start.poke(false.B)

        // 运行到 halt
        var cycles = 0
        while (!dut.io.allHalted.peek().litToBoolean && cycles < 100) {
          // 打印调试信息
          if (cycles < 20) {
            val gmemEn = dut.io.gmemExt.en.peek().litToBoolean
            val gmemWe = dut.io.gmemExt.we.peek().litToBoolean
            val gmemAddr = dut.io.gmemExt.addr.peek().litValue
            println(f"Cycle $cycles: gmemEn=$gmemEn, gmemWe=$gmemWe, gmemAddr=$gmemAddr")
            if (gmemEn && !gmemWe) {
              val rdata = Array.tabulate(W)(i => dut.io.gmemExt.rdata(i).peek().litValue.toInt)
              println(f"  gmemRdata: ${rdata.mkString(", ")}")
            }
          }
          dut.clock.step()
          cycles += 1
        }

        println(s"Halted after $cycles cycles")
        assert(dut.io.allHalted.peek().litToBoolean, s"Did not halt within 100 cycles")

        // 读取结果
        dut.io.gmemExt.en.poke(true.B)
        dut.io.gmemExt.we.poke(false.B)
        dut.io.gmemExt.addr.poke(2.U)
        dut.clock.step()
        dut.io.gmemExt.en.poke(false.B)

        val result = Array.tabulate(W)(i => dut.io.gmemExt.rdata(i).peek().litValue.toInt)
        println(s"Result: ${result.mkString(", ")}")
        println(s"Expected: ${Array.tabulate(W)(i => 10 * (i + 1) + (i + 1)).mkString(", ")}")

        for (i <- 0 until W) {
          val expected = 10 * (i + 1) + (i + 1)
          assert(result(i) == expected, s"Lane $i: got ${result(i)}, expected $expected")
        }
      }
    }
  }
}
