package ascend

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec

class SystolicArrayTest extends AnyFunSpec with ChiselSim {

  val N = AscendParams.ArraySize

  /** Reference matrix multiply in Scala. */
  def matmul(a: Array[Array[Int]], w: Array[Array[Int]]): Array[Array[Int]] = {
    Array.tabulate(N, N) { (i, j) =>
      (0 until N).map(k => a(i)(k) * w(k)(j)).sum
    }
  }

  /** Run C = A * W through the systolic array DUT. */
  def runMatmul(
      dut: SystolicArray,
      a: Array[Array[Int]],
      w: Array[Array[Int]]
  ): Array[Array[Int]] = {
    // Load weights
    for (k <- 0 until N; j <- 0 until N) {
      dut.io.weightData(k)(j).poke(w(k)(j).S(8.W))
    }

    // Start
    dut.io.start.poke(true.B)
    dut.io.actValid.poke(false.B)
    dut.clock.step()
    dut.io.start.poke(false.B)

    // Wait for LOAD_WEIGHT -> COMPUTE
    dut.clock.step()

    // Feed skewed activations for 2N-1 cycles
    val feedCycles = 2 * N - 1
    for (t <- 0 until feedCycles) {
      dut.io.actValid.poke(true.B)
      for (k <- 0 until N) {
        val i = t - k
        val v = if (i >= 0 && i < N) a(i)(k) else 0
        dut.io.actIn(k).poke(v.S(8.W))
      }
      dut.clock.step()
    }

    dut.io.actValid.poke(false.B)
    for (k <- 0 until N) dut.io.actIn(k).poke(0.S(8.W))

    // Wait for done
    var cycles = 0
    while (dut.io.done.peek().litToBoolean == false && cycles < 20) {
      dut.clock.step()
      cycles += 1
    }
    assert(
      dut.io.done.peek().litToBoolean,
      s"Systolic array did not signal done after $cycles drain cycles"
    )

    // Read results
    Array.tabulate(N, N) { (i, j) =>
      dut.io.result(i)(j).peek().litValue.toInt
    }
  }

  describe("SystolicArray") {

    it("computes A * I = A (identity weight)") {
      simulate(new SystolicArray) { dut =>
        val a = Array.tabulate(N, N)((i, j) => i * N + j + 1)
        val w = Array.tabulate(N, N)((i, j) => if (i == j) 1 else 0)
        val expected = matmul(a, w)
        val result = runMatmul(dut, a, w)

        for (i <- 0 until N; j <- 0 until N) {
          assert(
            result(i)(j) == expected(i)(j),
            s"C[$i][$j]: got ${result(i)(j)}, expected ${expected(i)(j)}"
          )
        }
      }
    }

    it("computes I * W = W (identity activation)") {
      simulate(new SystolicArray) { dut =>
        val a = Array.tabulate(N, N)((i, j) => if (i == j) 1 else 0)
        val w = Array.tabulate(N, N)((i, j) => i * N + j + 2)
        val expected = matmul(a, w)
        val result = runMatmul(dut, a, w)

        for (i <- 0 until N; j <- 0 until N) {
          assert(
            result(i)(j) == expected(i)(j),
            s"C[$i][$j]: got ${result(i)(j)}, expected ${expected(i)(j)}"
          )
        }
      }
    }

    it("computes general matrix multiplication") {
      simulate(new SystolicArray) { dut =>
        val a = Array.tabulate(N, N)((i, j) => (i + j + 1) % 8)
        val w = Array.tabulate(N, N)((i, j) => (i * 2 + j) % 8)
        val expected = matmul(a, w)
        val result = runMatmul(dut, a, w)

        for (i <- 0 until N; j <- 0 until N) {
          assert(
            result(i)(j) == expected(i)(j),
            s"C[$i][$j]: got ${result(i)(j)}, expected ${expected(i)(j)}"
          )
        }
      }
    }
  }
}
