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
      a: Array[Array[Int]],
      w: Array[Array[Int]]
  ): Array[Array[Int]] = {
    for (k <- 0 until N; j <- 0 until N) {
      dut.io.weightData(k)(j).poke(w(k)(j).S(8.W))
      dut.io.actData(k)(j).poke(a(k)(j).S(8.W))
    }

    dut.io.start.poke(true.B)
    dut.clock.step()
    dut.io.start.poke(false.B)

    var cycles = 0
    while (!dut.io.done.peek().litToBoolean && cycles < (4 * N + 32)) {
      dut.clock.step()
      cycles += 1
    }
    assert(dut.io.done.peek().litToBoolean, "Cube unit did not signal done")

    Array.tabulate(N, N)((i, j) => dut.io.result(i)(j).peek().litValue.toInt)
  }

  describe("CubeUnit") {

    it("computes A * I = A") {
      simulate(new CubeUnit) { dut =>
        val a = Array.tabulate(N, N)((i, j) => (i * 3 + j + 1) % 64)
        val w = Array.tabulate(N, N)((i, j) => if (i == j) 1 else 0)
        val expected = matmul(a, w)
        val result = runCubeMatmul(dut, a, w)

        for (i <- 0 until N; j <- 0 until N)
          assert(
            result(i)(j) == expected(i)(j),
            s"C[$i][$j]: got ${result(i)(j)}, expected ${expected(i)(j)}"
          )
      }
    }

    it("computes general matmul") {
      simulate(new CubeUnit) { dut =>
        val a = Array.tabulate(N, N)((i, j) => (i + j + 1) % 8)
        val w = Array.tabulate(N, N)((i, j) => (i * 2 + j) % 8)
        val expected = matmul(a, w)
        val result = runCubeMatmul(dut, a, w)

        for (i <- 0 until N; j <- 0 until N)
          assert(
            result(i)(j) == expected(i)(j),
            s"C[$i][$j]: got ${result(i)(j)}, expected ${expected(i)(j)}"
          )
      }
    }
  }
}
