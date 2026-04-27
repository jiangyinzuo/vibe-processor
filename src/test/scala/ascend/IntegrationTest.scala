package ascend

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec

class IntegrationTest extends AnyFunSpec with ChiselSim {

  val N = AscendParams.ArraySize

  /** Encode a LOAD instruction. */
  def encLoad(bufSel: Int, memAddr: Int): Int =
    (0x2 << 28) | ((bufSel & 0x3) << 26) | ((memAddr & 0xFFFF) << 4)

  /** Encode a STORE instruction. */
  def encStore(bufSel: Int, memAddr: Int): Int =
    (0x3 << 28) | ((bufSel & 0x3) << 26) | ((memAddr & 0xFFFF) << 4)

  /** Encode MATMUL. */
  def encMatmul: Int = 0x4 << 28

  /** Encode NOP. */
  def encNop: Int = 0x0

  /** Encode HALT. */
  def encHalt: Int = 0x1 << 28

  /** Load program into instruction memory. */
  def loadProgram(dut: ToyAscendTop, instrs: Seq[Int]): Unit = {
    for ((instr, i) <- instrs.zipWithIndex) {
      dut.io.imemLoadEn.poke(true.B)
      dut.io.imemLoadAddr.poke(i.U)
      dut.io.imemLoadData.poke(instr.U)
      dut.clock.step()
    }
    dut.io.imemLoadEn.poke(false.B)
    dut.clock.step()
  }

  /** Write a row of N int values to UB at given address. */
  def writeUB(dut: ToyAscendTop, addr: Int, row: Array[Int]): Unit = {
    dut.io.ubExt.en.poke(true.B)
    dut.io.ubExt.we.poke(true.B)
    dut.io.ubExt.addr.poke(addr.U)
    for (j <- 0 until N) {
      dut.io.ubExt.wdata(j).poke(row(j).S(32.W))
    }
    dut.clock.step()
    dut.io.ubExt.en.poke(false.B)
    dut.io.ubExt.we.poke(false.B)
    dut.clock.step()
  }

  /** Read a row of N int values from UB at given address. */
  def readUB(dut: ToyAscendTop, addr: Int): Array[Int] = {
    dut.io.ubExt.en.poke(true.B)
    dut.io.ubExt.we.poke(false.B)
    dut.io.ubExt.addr.poke(addr.U)
    dut.clock.step()
    dut.io.ubExt.en.poke(false.B)
    dut.clock.step()
    Array.tabulate(N)(j => dut.io.ubExt.rdata(j).peek().litValue.toInt)
  }

  describe("Integration") {

    it("runs NOP + HALT program") {
      simulate(new ToyAscendTop) { dut =>
        dut.io.start.poke(false.B)
        dut.io.imemLoadEn.poke(false.B)
        dut.io.ubExt.en.poke(false.B)
        dut.io.ubExt.we.poke(false.B)

        loadProgram(dut, Seq(encNop, encHalt))

        dut.io.start.poke(true.B)
        dut.clock.step()
        dut.io.start.poke(false.B)

        var cycles = 0
        while (!dut.io.halted.peek().litToBoolean && cycles < 50) {
          dut.clock.step()
          cycles += 1
        }
        assert(dut.io.halted.peek().litToBoolean, s"Did not halt within $cycles cycles")
      }
    }

    it("runs LOAD -> MATMUL -> STORE and verifies C = A * W") {
      simulate(new ToyAscendTop) { dut =>
        dut.io.start.poke(false.B)
        dut.io.imemLoadEn.poke(false.B)
        dut.io.ubExt.en.poke(false.B)
        dut.io.ubExt.we.poke(false.B)

        val a = Array(
          Array(1, 2, 3, 4), Array(5, 6, 7, 8),
          Array(2, 3, 1, 4), Array(7, 1, 5, 3)
        )
        val w = Array(
          Array(1, 0, 2, 1), Array(3, 1, 0, 2),
          Array(2, 4, 1, 3), Array(0, 2, 3, 1)
        )
        val expected = Array.tabulate(N, N)((i, j) =>
          (0 until N).map(k => a(i)(k) * w(k)(j)).sum
        )

        // Program: LOAD act(L0_B) from UB[0..3], LOAD weight(L0_A) from UB[4..7],
        //          MATMUL, STORE result(L0_C) to UB[8..11], HALT
        val program = Seq(
          encLoad(1, 0),  // LOAD L0_B, mem_addr=0
          encLoad(0, 4),  // LOAD L0_A, mem_addr=4
          encMatmul,
          encStore(2, 8), // STORE L0_C, mem_addr=8
          encHalt
        )
        loadProgram(dut, program)

        // Preload A into UB[0..3]
        for (i <- 0 until N) writeUB(dut, i, a(i))
        // Preload W into UB[4..7]
        for (i <- 0 until N) writeUB(dut, 4 + i, w(i))

        // Start execution
        dut.io.start.poke(true.B)
        dut.clock.step()
        dut.io.start.poke(false.B)

        // Wait for HALT
        var cycles = 0
        while (!dut.io.halted.peek().litToBoolean && cycles < 500) {
          dut.clock.step()
          cycles += 1
        }
        assert(dut.io.halted.peek().litToBoolean, s"Did not halt within $cycles cycles")

        // Read results from UB[8..11]
        for (i <- 0 until N) {
          val row = readUB(dut, 8 + i)
          for (j <- 0 until N) {
            assert(row(j) == expected(i)(j),
              s"C[$i][$j]: got ${row(j)}, expected ${expected(i)(j)}")
          }
        }
      }
    }
  }
}
