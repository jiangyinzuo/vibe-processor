package common

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import chisel3.util._
import org.scalatest.funspec.AnyFunSpec

class HbmControllerModelHarness(
    n: Int = 4,
    aw: Int = Params.AccWidth,
    depth: Int = 64,
    rowHitLatency: Int = 1,
    rowMissLatency: Int = 3,
    modelLatency: Int = 1,
    addrW: Int = 8,
    numChannels: Int = 2,
    banksPerChannel: Int = 2
) extends Module {
  val io = IO(new Bundle {
    val req = Flipped(Decoupled(new HbmRequest(n, aw, addrW)))
    val resp = Valid(new HbmResponse(n, aw))
    val direct = new HbmDirectPort(n, aw, addrW)
  })

  val controller = Module(
    new HbmController(
      n = n,
      aw = aw,
      addrW = addrW,
      numChannels = numChannels,
      banksPerChannel = banksPerChannel,
      rowHitLatency = rowHitLatency,
      rowMissLatency = rowMissLatency,
      requestQueueDepth = 4
    )
  )
  val model = Module(new HbmModel(n, aw, depth, modelLatency, addrW))

  controller.io.coreReq <> io.req
  io.resp.valid := controller.io.coreResp.valid
  io.resp.bits := controller.io.coreResp.bits
  model.io.req <> controller.io.memReq
  controller.io.memResp <> model.io.resp
  controller.io.coreResp.ready := true.B
  model.io.direct.en := io.direct.en
  model.io.direct.we := io.direct.we
  model.io.direct.addr := io.direct.addr
  model.io.direct.wdata := io.direct.wdata
  io.direct.rdata := model.io.direct.rdata
}

class HbmStackedMemoryHarness(
    n: Int = 4,
    aw: Int = Params.AccWidth,
    totalDepth: Int = 64,
    addrW: Int = 8,
    numStacks: Int = 2,
    numChannelsPerStack: Int = 1,
    banksPerChannel: Int = 1,
    rowHitLatency: Int = 1,
    rowMissLatency: Int = 4
) extends Module {
  val io = IO(new Bundle {
    val req = Flipped(Decoupled(new HbmRequest(n, aw, addrW)))
    val resp = Valid(new HbmResponse(n, aw))
    val direct = new HbmDirectPort(n, aw, addrW)
  })

  val hbm = Module(
    new HbmStackedMemory(
      n = n,
      aw = aw,
      totalDepth = totalDepth,
      addrW = addrW,
      numStacks = numStacks,
      numChannelsPerStack = numChannelsPerStack,
      banksPerChannel = banksPerChannel,
      rowHitLatency = rowHitLatency,
      rowMissLatency = rowMissLatency,
      requestQueueDepth = 2,
      responseQueueDepth = 2,
      modelLatency = 1
    )
  )

  hbm.io.coreReq <> io.req
  io.resp := hbm.io.coreResp
  hbm.io.direct.en := io.direct.en
  hbm.io.direct.we := io.direct.we
  hbm.io.direct.addr := io.direct.addr
  hbm.io.direct.wdata := io.direct.wdata
  io.direct.rdata := hbm.io.direct.rdata
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

  def initStacked(dut: HbmStackedMemoryHarness): Unit = {
    dut.io.req.valid.poke(false.B)
    dut.io.req.bits.we.poke(false.B)
    dut.io.req.bits.addr.poke(0.U)
    for (i <- 0 until N) dut.io.req.bits.wdata(i).poke(0.S)
    dut.io.direct.en.poke(false.B)
    dut.io.direct.we.poke(false.B)
    dut.io.direct.addr.poke(0.U)
    for (i <- 0 until N) dut.io.direct.wdata(i).poke(0.S)
  }

  def waitForResp(dut: HbmControllerModelHarness, maxCycles: Int = 16): Unit = {
    var cycles = 0
    while (!dut.io.resp.valid.peek().litToBoolean && cycles < maxCycles) {
      dut.clock.step()
      cycles += 1
    }
    assert(dut.io.resp.valid.peek().litToBoolean, s"no HBM response within $maxCycles cycles")
  }

  describe("HBM controller/model split") {
    it("reads HBM model data through HBM controller") {
      simulate(new HbmControllerModelHarness(rowMissLatency = 3)) { dut =>
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

        waitForResp(dut)
        for (i <- 0 until N) dut.io.resp.bits.rdata(i).expect((i + 10).S)
      }
    }

    it("writes through HBM controller and reads back through HBM model direct port") {
      simulate(new HbmControllerModelHarness(rowMissLatency = 3)) { dut =>
        init(dut)

        dut.io.req.valid.poke(true.B)
        dut.io.req.bits.we.poke(true.B)
        dut.io.req.bits.addr.poke(9.U)
        for (i <- 0 until N) dut.io.req.bits.wdata(i).poke((100 + i).S)
        assert(dut.io.req.ready.peek().litToBoolean)
        dut.clock.step()
        dut.io.req.valid.poke(false.B)
        dut.io.req.bits.we.poke(false.B)
        dut.clock.step(8)

        dut.io.direct.en.poke(true.B)
        dut.io.direct.we.poke(false.B)
        dut.io.direct.addr.poke(9.U)
        dut.clock.step()
        for (i <- 0 until N) dut.io.direct.rdata(i).expect((100 + i).S)
      }
    }

    it("backpressures same-bank requests while another bank can queue") {
      simulate(new HbmControllerModelHarness(rowHitLatency = 1, rowMissLatency = 4)) { dut =>
        init(dut)

        dut.io.req.valid.poke(true.B)
        dut.io.req.bits.we.poke(true.B)
        dut.io.req.bits.addr.poke(0.U)
        for (i <- 0 until N) dut.io.req.bits.wdata(i).poke((10 + i).S)
        assert(dut.io.req.ready.peek().litToBoolean)
        dut.clock.step()

        // Address mapping with 2 channels and 2 banks:
        // addr[0] = channel, addr[1] = bank, addr[7:2] = row.
        // 0 and 4 target the same channel/bank but different rows.
        dut.io.req.bits.addr.poke(4.U)
        assert(!dut.io.req.ready.peek().litToBoolean)

        // Address 1 targets a different channel, so it can be accepted while bank 0 is busy.
        dut.io.req.bits.addr.poke(1.U)
        assert(dut.io.req.ready.peek().litToBoolean)
        dut.clock.step()

        dut.io.req.valid.poke(false.B)
      }
    }

    it("interleaves addresses across independent HBM stacks") {
      simulate(new HbmStackedMemoryHarness()) { dut =>
        initStacked(dut)

        dut.io.req.valid.poke(true.B)
        dut.io.req.bits.we.poke(true.B)
        dut.io.req.bits.addr.poke(0.U)
        for (i <- 0 until N) dut.io.req.bits.wdata(i).poke((20 + i).S)
        assert(dut.io.req.ready.peek().litToBoolean)
        dut.clock.step()

        // With two stacks, addr[0] selects the stack and addr[7:1] is local.
        // Address 2 maps back to stack 0 and is blocked by stack 0's busy bank.
        dut.io.req.bits.addr.poke(2.U)
        assert(!dut.io.req.ready.peek().litToBoolean)

        // Address 1 maps to stack 1. It can be accepted while stack 0 is busy.
        dut.io.req.bits.addr.poke(1.U)
        assert(dut.io.req.ready.peek().litToBoolean)
        dut.clock.step()

        dut.io.req.valid.poke(false.B)
      }
    }
  }
}
