package ascend

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec

class CubeUnitTest extends AnyFunSpec with ChiselSim {

  val N = AscendParams.ArraySize

  def matmul(a: Array[Array[Int]], w: Array[Array[Int]]): Array[Array[Int]] =
    Array.tabulate(N, N)((i, j) => (0 until N).map(k => a(i)(k) * w(k)(j)).sum)

  def runCubeMatmul(
      dut: CubeUnit,
      a:   Array[Array[Int]],
      w:   Array[Array[Int]]
  ): Array[Array[Int]] = {
    for (k <- 0 until N; j <- 0 until N) {
      dut.io.weightData(k)(j).poke(w(k)(j).S(8.W))
      dut.io.actData(k)(j).poke(a(k)(j).S(8.W))
    }

    dut.io.start.poke(true.B)
    dut.clock.step()
    dut.io.start.poke(false.B)

    var cycles = 0
    while (!dut.io.done.peek().litToBoolean && cycles < 30) {
      dut.clock.step()
      cycles += 1
    }
    assert(dut.io.done.peek().litToBoolean, "Cube unit did not signal done")

    Array.tabulate(N, N)((i, j) => dut.io.result(i)(j).peek().litValue.toInt)
  }

  describe("CubeUnit") {

    it("computes A * I = A") {
      simulate(new CubeUnit) { dut =>
        val a = Array(
          Array(1, 2, 3, 4), Array(5, 6, 7, 8),
          Array(9, 10, 11, 12), Array(13, 14, 15, 16)
        )
        val w = Array.tabulate(N, N)((i, j) => if (i == j) 1 else 0)
        val expected = matmul(a, w)
        val result   = runCubeMatmul(dut, a, w)

        for (i <- 0 until N; j <- 0 until N)
          assert(result(i)(j) == expected(i)(j),
            s"C[$i][$j]: got ${result(i)(j)}, expected ${expected(i)(j)}")
      }
    }

    it("computes general matmul") {
      simulate(new CubeUnit) { dut =>
        val a = Array(
          Array(1, 2, 3, 4), Array(5, 6, 7, 8),
          Array(2, 3, 1, 4), Array(7, 1, 5, 3)
        )
        val w = Array(
          Array(1, 0, 2, 1), Array(3, 1, 0, 2),
          Array(2, 4, 1, 3), Array(0, 2, 3, 1)
        )
        val expected = matmul(a, w)
        val result   = runCubeMatmul(dut, a, w)

        for (i <- 0 until N; j <- 0 until N)
          assert(result(i)(j) == expected(i)(j),
            s"C[$i][$j]: got ${result(i)(j)}, expected ${expected(i)(j)}")
      }
    }
  }
}
