package ascend

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec

class CubeCoreTest extends AnyFunSpec with ChiselSim {

  val N = AscendParams.ArraySize

  def matmul(a: Array[Array[Int]], w: Array[Array[Int]]): Array[Array[Int]] =
    Array.tabulate(N, N)((i, j) => (0 until N).map(k => a(i)(k) * w(k)(j)).sum)

  def init(dut: CubeCore): Unit = {
    dut.io.start.poke(false.B)
    dut.io.accumulate.poke(false.B)
    dut.io.l0cReadRow.poke(0.U)
    dut.io.mte1Write.valid.poke(false.B)
    dut.io.mte1Write.bits.target.poke(CubeLocalTarget.ACT)
    dut.io.mte1Write.bits.row.poke(0.U)
    for (j <- 0 until N) {
      dut.io.mte1Write.bits.data(j).poke(0.S(AscendParams.DataWidth.W))
    }
  }

  def writeTileRows(dut: CubeCore, target: UInt, tile: Array[Array[Int]]): Unit = {
    for (row <- 0 until N) {
      dut.io.mte1Write.valid.poke(true.B)
      dut.io.mte1Write.bits.target.poke(target)
      dut.io.mte1Write.bits.row.poke(row.U)
      for (j <- 0 until N) {
        dut.io.mte1Write.bits.data(j).poke(tile(row)(j).S(AscendParams.DataWidth.W))
      }
      dut.clock.step()
    }
    dut.io.mte1Write.valid.poke(false.B)
    dut.clock.step()
  }

  def startAndWait(dut: CubeCore, accumulate: Boolean): Unit = {
    dut.io.accumulate.poke(accumulate.B)
    dut.io.start.poke(true.B)
    dut.clock.step()
    dut.io.start.poke(false.B)

    var cycles = 0
    while (!dut.io.done.peek().litToBoolean && cycles < 80) {
      dut.clock.step()
      cycles += 1
    }
    assert(dut.io.done.peek().litToBoolean, s"CubeCore did not finish within $cycles cycles")
    dut.clock.step()
  }

  def readL0C(dut: CubeCore): Array[Array[Int]] =
    Array.tabulate(N, N) { (i, j) =>
      dut.io.l0cReadRow.poke(i.U)
      dut.io.l0cReadData(j).peek().litValue.toInt
    }

  describe("CubeCore") {
    it("uses L0C as an accumulator when MATMUL accumulate mode is set") {
      simulate(new CubeCore) { dut =>
        init(dut)

        val a = Array.tabulate(N, N)((i, j) => (i + j + 1) % 5)
        val w = Array.tabulate(N, N)((i, j) => if (i == j) 1 else (i + j) % 3)
        val single = matmul(a, w)
        val accumulated = Array.tabulate(N, N)((i, j) => single(i)(j) * 2)

        writeTileRows(dut, CubeLocalTarget.ACT, a)
        writeTileRows(dut, CubeLocalTarget.WEIGHT, w)
        writeTileRows(dut, CubeLocalTarget.ACT, a)
        writeTileRows(dut, CubeLocalTarget.WEIGHT, w)

        startAndWait(dut, accumulate = false)
        startAndWait(dut, accumulate = true)

        val result = readL0C(dut)
        for (i <- 0 until N; j <- 0 until N) {
          assert(
            result(i)(j) == accumulated(i)(j),
            s"C[$i][$j]: got ${result(i)(j)}, expected ${accumulated(i)(j)}"
          )
        }
      }
    }
  }
}
