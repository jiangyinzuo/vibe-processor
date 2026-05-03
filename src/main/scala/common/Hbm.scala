package common

import chisel3._
import chisel3.util._

/** HBM request payload on the controller/model boundary. */
class HbmRequest(
    val n: Int,
    val aw: Int,
    val addrW: Int
) extends Bundle {
  val we = Bool()
  val addr = UInt(addrW.W)
  val wdata = Vec(n, SInt(aw.W))
}

/** HBM read response payload. Writes complete without a response in this toy model. */
class HbmResponse(
    val n: Int,
    val aw: Int
) extends Bundle {
  val rdata = Vec(n, SInt(aw.W))
}

/** Direct test-only access port for HBM model preload/readback. */
class HbmDirectPort(
    val n: Int,
    val aw: Int,
    val addrW: Int
) extends Bundle {
  val en = Input(Bool())
  val we = Input(Bool())
  val addr = Input(UInt(addrW.W))
  val wdata = Input(Vec(n, SInt(aw.W)))
  val rdata = Output(Vec(n, SInt(aw.W)))
}

/** Toy HBM controller.
  *
  * This module is the compute-die side of the HBM boundary. It currently forwards one in-flight
  * request stream to the HBM model. Later it is the right place to add address mapping, burst
  * splitting, bank scheduling, refresh/ECC modeling, QoS, and PHY-facing protocol details.
  */
class HbmController(
    val n: Int,
    val aw: Int,
    val addrW: Int
) extends Module {
  val io = IO(new Bundle {
    val coreReq = Flipped(Decoupled(new HbmRequest(n, aw, addrW)))
    val coreResp = Valid(new HbmResponse(n, aw))

    val memReq = Decoupled(new HbmRequest(n, aw, addrW))
    val memResp = Flipped(Valid(new HbmResponse(n, aw)))
  })

  io.memReq.valid := io.coreReq.valid
  io.memReq.bits := io.coreReq.bits
  io.coreReq.ready := io.memReq.ready

  io.coreResp.valid := io.memResp.valid
  io.coreResp.bits := io.memResp.bits
}

/** Simulation HBM model.
  *
  * This module represents the external HBM stack in the toy environment. Real ASIC RTL would
  * replace this with PHY/vendor memory-model connectivity outside the synthesizable NPU core.
  */
class HbmModel(
    val n: Int,
    val aw: Int,
    val depth: Int,
    val latency: Int,
    val addrW: Int
) extends Module {
  require(latency >= 1, "latency must be >= 1")

  val io = IO(new Bundle {
    val req = Flipped(Decoupled(new HbmRequest(n, aw, addrW)))
    val resp = Valid(new HbmResponse(n, aw))
    val direct = new HbmDirectPort(n, aw, addrW)
  })

  val mem = Module(
    new LatencyMem(
      gen = Vec(n, SInt(aw.W)),
      depth = depth,
      latency = latency,
      addrW = addrW
    )
  )

  mem.io.req.valid := io.req.valid
  mem.io.req.we := io.req.bits.we
  mem.io.req.addr := io.req.bits.addr
  mem.io.req.wdata := io.req.bits.wdata
  io.req.ready := mem.io.req.ready

  io.resp.valid := mem.io.resp.valid
  io.resp.bits.rdata := mem.io.resp.rdata

  mem.io.direct.en := io.direct.en
  mem.io.direct.we := io.direct.we
  mem.io.direct.addr := io.direct.addr
  mem.io.direct.wdata := io.direct.wdata
  io.direct.rdata := mem.io.direct.rdata
}
