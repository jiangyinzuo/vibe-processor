package common

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import chisel3.util._
import org.scalatest.funspec.AnyFunSpec

class HbmControllerModelHarness(
    n: Int = 4,
    aw: Int = Params.AccWidth,
    depth: Int = 64,
    latency: Int = 3,
    addrW: Int = 8
) extends Module {
  val io = IO(new Bundle {
    val req = Flipped(Decoupled(new HbmRequest(n, aw, addrW)))
    val resp = Valid(new HbmResponse(n, aw))
    val direct = new HbmDirectPort(n, aw, addrW)
  })

  val controller = Module(new HbmController(n, aw, addrW))
  val model = Module(new HbmModel(n, aw, depth, latency, addrW))

  controller.io.coreReq <> io.req
  io.resp.valid := controller.io.coreResp.valid
  io.resp.bits := controller.io.coreResp.bits
  model.io.req <> controller.io.memReq
  controller.io.memResp <> model.io.resp
  model.io.direct.en := io.direct.en
  model.io.direct.we := io.direct.we
  model.io.direct.addr := io.direct.addr
  model.io.direct.wdata := io.direct.wdata
  io.direct.rdata := model.io.direct.rdata
}

class HbmTest extends AnyFunSpec with ChiselSim {
  val N = 4

  def init(dut: HbmControllerModelHarness): Unit = {
    dut.io.req.valid.poke(false.B)
    dut.io.req.bits.we.poke(false.B)
    dut.io.req.bits.addr.poke(0.U)
    for (i <- 0 until N) dut.io.req.bits.wdata(i).poke(0.S)
    dut.io.direct.en.poke(false.B)
    dut.io.direct.we.poke(false.B)
    dut.io.direct.addr.poke(0.U)
    for (i <- 0 until N) dut.io.direct.wdata(i).poke(0.S)
  }

  describe("HBM controller/model split") {
    it("reads HBM model data through HBM controller") {
      simulate(new HbmControllerModelHarness(latency = 3)) { dut =>
        init(dut)

        dut.io.direct.en.poke(true.B)
        dut.io.direct.we.poke(true.B)
        dut.io.direct.addr.poke(7.U)
        for (i <- 0 until N) dut.io.direct.wdata(i).poke((i + 10).S)
        dut.clock.step()
        dut.io.direct.en.poke(false.B)
        dut.io.direct.we.poke(false.B)

        dut.io.req.valid.poke(true.B)
        dut.io.req.bits.we.poke(false.B)
        dut.io.req.bits.addr.poke(7.U)
        assert(dut.io.req.ready.peek().litToBoolean)
        dut.clock.step()
        dut.io.req.valid.poke(false.B)

        for (_ <- 1 until 3) {
          assert(!dut.io.resp.valid.peek().litToBoolean)
          dut.clock.step()
        }

        assert(dut.io.resp.valid.peek().litToBoolean)
        for (i <- 0 until N) dut.io.resp.bits.rdata(i).expect((i + 10).S)
      }
    }

    it("writes through HBM controller and reads back through HBM model direct port") {
      simulate(new HbmControllerModelHarness(latency = 3)) { dut =>
        init(dut)

        dut.io.req.valid.poke(true.B)
        dut.io.req.bits.we.poke(true.B)
        dut.io.req.bits.addr.poke(9.U)
        for (i <- 0 until N) dut.io.req.bits.wdata(i).poke((100 + i).S)
        assert(dut.io.req.ready.peek().litToBoolean)
        dut.clock.step()
        dut.io.req.valid.poke(false.B)
        dut.io.req.bits.we.poke(false.B)

        dut.io.direct.en.poke(true.B)
        dut.io.direct.we.poke(false.B)
        dut.io.direct.addr.poke(9.U)
        dut.clock.step()
        for (i <- 0 until N) dut.io.direct.rdata(i).expect((100 + i).S)
      }
    }
  }
}
