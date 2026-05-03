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

/** HBM controller timing model.
  *
  * This module is the compute-die side of the HBM boundary. It decodes line addresses into a
  * simplified channel/bank/row mapping, tracks per-bank row state, applies row-hit/row-miss timing,
  * and queues accepted requests before issuing them to the memory model.
  */
class HbmController(
    val n: Int,
    val aw: Int,
    val addrW: Int,
    val numChannels: Int = 4,
    val banksPerChannel: Int = 4,
    val rowHitLatency: Int = 3,
    val rowMissLatency: Int = 10,
    val requestQueueDepth: Int = 8,
    val responseQueueDepth: Int = 4
) extends Module {
  require(numChannels > 0, "numChannels must be positive")
  require(banksPerChannel > 0, "banksPerChannel must be positive")
  require(isPow2(numChannels), "numChannels must be a power of two")
  require(isPow2(banksPerChannel), "banksPerChannel must be a power of two")
  require(rowHitLatency >= 1, "rowHitLatency must be >= 1")
  require(rowMissLatency >= rowHitLatency, "rowMissLatency must be >= rowHitLatency")
  require(requestQueueDepth > 0, "requestQueueDepth must be positive")
  require(responseQueueDepth > 0, "responseQueueDepth must be positive")

  val io = IO(new Bundle {
    val coreReq = Flipped(Decoupled(new HbmRequest(n, aw, addrW)))
    val coreResp = Decoupled(new HbmResponse(n, aw))

    val memReq = Decoupled(new HbmRequest(n, aw, addrW))
    val memResp = Flipped(Valid(new HbmResponse(n, aw)))
  })

  private val totalBanks = numChannels * banksPerChannel
  private val channelW = log2Ceil(numChannels)
  private val bankW = log2Ceil(banksPerChannel)
  private val bankIndexW = math.max(1, log2Ceil(totalBanks))
  private val rowLsb = channelW + bankW
  private val rowW = math.max(1, addrW - rowLsb)
  private val latencyW = log2Ceil(rowMissLatency + 1).max(1)

  private def channelOf(addr: UInt): UInt =
    if (channelW == 0) 0.U(1.W) else addr(channelW - 1, 0)

  private def bankOf(addr: UInt): UInt =
    if (bankW == 0) 0.U(1.W) else addr(rowLsb - 1, channelW)

  private def bankIndexOf(addr: UInt): UInt = {
    val channel = channelOf(addr)
    val bank = bankOf(addr)

    if (numChannels == 1 && banksPerChannel == 1) {
      0.U(bankIndexW.W)
    } else if (numChannels == 1) {
      bank.asUInt.pad(bankIndexW)
    } else if (banksPerChannel == 1) {
      channel.asUInt.pad(bankIndexW)
    } else {
      Cat(channel, bank).asUInt
    }
  }

  private def rowOf(addr: UInt): UInt =
    if (addrW > rowLsb) addr(addrW - 1, rowLsb) else 0.U(rowW.W)

  class HbmQueuedRequest extends Bundle {
    val req = new HbmRequest(n, aw, addrW)
    val latency = UInt(latencyW.W)
  }

  val bankBusy = RegInit(VecInit(Seq.fill(totalBanks)(0.U(latencyW.W))))
  val bankRowValid = RegInit(VecInit(Seq.fill(totalBanks)(false.B)))
  val bankActiveRow = Reg(Vec(totalBanks, UInt(rowW.W)))

  for (b <- 0 until totalBanks) {
    when(bankBusy(b) =/= 0.U) {
      bankBusy(b) := bankBusy(b) - 1.U
    }
  }

  val reqBank = bankIndexOf(io.coreReq.bits.addr)
  val reqRow = rowOf(io.coreReq.bits.addr)
  val reqRowHit = bankRowValid(reqBank) && bankActiveRow(reqBank) === reqRow
  val reqLatency = Mux(reqRowHit, rowHitLatency.U(latencyW.W), rowMissLatency.U(latencyW.W))

  val requestQueue = Module(new Queue(new HbmQueuedRequest, requestQueueDepth))
  val reqBankAvailable = bankBusy(reqBank) === 0.U
  requestQueue.io.enq.valid := io.coreReq.valid && reqBankAvailable
  requestQueue.io.enq.bits.req := io.coreReq.bits
  requestQueue.io.enq.bits.latency := reqLatency
  io.coreReq.ready := requestQueue.io.enq.ready && reqBankAvailable

  when(requestQueue.io.enq.fire) {
    bankBusy(reqBank) := reqLatency
    bankRowValid(reqBank) := true.B
    bankActiveRow(reqBank) := reqRow
  }

  val activeValid = RegInit(false.B)
  val activeReq = Reg(new HbmRequest(n, aw, addrW))
  val activeCounter = RegInit(0.U(latencyW.W))

  requestQueue.io.deq.ready := !activeValid
  when(!activeValid && requestQueue.io.deq.valid) {
    activeValid := true.B
    activeReq := requestQueue.io.deq.bits.req
    activeCounter := requestQueue.io.deq.bits.latency
  }.elsewhen(activeValid && activeCounter =/= 0.U) {
    activeCounter := activeCounter - 1.U
  }

  io.memReq.valid := activeValid && activeCounter === 0.U
  io.memReq.bits := activeReq
  when(io.memReq.fire) {
    activeValid := false.B
  }

  val responseQueue = Module(new Queue(new HbmResponse(n, aw), responseQueueDepth))
  responseQueue.io.enq.valid := io.memResp.valid
  responseQueue.io.enq.bits := io.memResp.bits
  assert(!io.memResp.valid || responseQueue.io.enq.ready, "HBM controller response queue overflow")
  io.coreResp <> responseQueue.io.deq
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

/** Multi-stack HBM subsystem.
  *
  * Each stack has an independent controller and storage model. The low-order line-address bits select
  * the HBM stack; the remaining local address is decoded by that stack's controller into
  * channel/bank/row state. This approximates address interleaving across physical HBM stacks.
  */
class HbmStackedMemory(
    val n: Int,
    val aw: Int,
    val totalDepth: Int,
    val addrW: Int,
    val numStacks: Int = 4,
    val numChannelsPerStack: Int = 4,
    val banksPerChannel: Int = 4,
    val rowHitLatency: Int = 3,
    val rowMissLatency: Int = 10,
    val requestQueueDepth: Int = 8,
    val responseQueueDepth: Int = 4,
    val modelLatency: Int = 1
) extends Module {
  require(totalDepth > 0, "totalDepth must be positive")
  require(numStacks > 0, "numStacks must be positive")
  require(isPow2(numStacks), "numStacks must be a power of two")
  require(totalDepth % numStacks == 0, "totalDepth must be divisible by numStacks")
  require(addrW > log2Ceil(numStacks), "addrW must leave at least one local address bit")

  val io = IO(new Bundle {
    val coreReq = Flipped(Decoupled(new HbmRequest(n, aw, addrW)))
    val coreResp = Valid(new HbmResponse(n, aw))
    val direct = new HbmDirectPort(n, aw, addrW)
  })

  private val stackW = log2Ceil(numStacks)
  private val stackIdW = math.max(1, stackW)
  private val localAddrW = addrW - stackW
  private val stackDepth = totalDepth / numStacks

  private def stackOf(addr: UInt): UInt =
    if (stackW == 0) 0.U(1.W) else addr(stackW - 1, 0)

  private def localAddrOf(addr: UInt): UInt =
    if (stackW == 0) addr else addr(addrW - 1, stackW).asUInt

  private val controllers = Seq.fill(numStacks)(
    Module(
      new HbmController(
        n = n,
        aw = aw,
        addrW = localAddrW,
        numChannels = numChannelsPerStack,
        banksPerChannel = banksPerChannel,
        rowHitLatency = rowHitLatency,
        rowMissLatency = rowMissLatency,
        requestQueueDepth = requestQueueDepth,
        responseQueueDepth = responseQueueDepth
      )
    )
  )
  private val models = Seq.fill(numStacks)(
    Module(
      new HbmModel(
        n = n,
        aw = aw,
        depth = stackDepth,
        latency = modelLatency,
        addrW = localAddrW
      )
    )
  )

  private val coreStack = stackOf(io.coreReq.bits.addr)
  private val coreLocalReq = Wire(new HbmRequest(n, aw, localAddrW))
  coreLocalReq.we := io.coreReq.bits.we
  coreLocalReq.addr := localAddrOf(io.coreReq.bits.addr)
  coreLocalReq.wdata := io.coreReq.bits.wdata

  private val directStack = stackOf(io.direct.addr)
  private val directLocalAddr = localAddrOf(io.direct.addr)
  private val readStackQueue = Module(new Queue(UInt(stackIdW.W), responseQueueDepth))
  private val readQueueAvailable = io.coreReq.bits.we || readStackQueue.io.enq.ready

  for (s <- 0 until numStacks) {
    models(s).io.req <> controllers(s).io.memReq
    controllers(s).io.memResp <> models(s).io.resp

    controllers(s).io.coreReq.valid := io.coreReq.valid && readQueueAvailable && coreStack === s.U
    controllers(s).io.coreReq.bits := coreLocalReq

    models(s).io.direct.en := io.direct.en && directStack === s.U
    models(s).io.direct.we := io.direct.we
    models(s).io.direct.addr := directLocalAddr
    models(s).io.direct.wdata := io.direct.wdata
  }

  private val selectedStackReady = Mux1H(
    (0 until numStacks).map(s => (coreStack === s.U) -> controllers(s).io.coreReq.ready)
  )
  io.coreReq.ready := readQueueAvailable && selectedStackReady

  readStackQueue.io.enq.valid := io.coreReq.fire && !io.coreReq.bits.we
  readStackQueue.io.enq.bits := coreStack.asUInt.pad(stackIdW)

  private val responseHeadStack = readStackQueue.io.deq.bits
  private val responseHeadValid = Mux1H(
    (0 until numStacks).map(s => (responseHeadStack === s.U) -> controllers(s).io.coreResp.valid)
  )
  io.coreResp.valid := readStackQueue.io.deq.valid && responseHeadValid
  io.coreResp.bits := Mux1H(
    (0 until numStacks).map(s => (responseHeadStack === s.U) -> controllers(s).io.coreResp.bits)
  )
  readStackQueue.io.deq.ready := io.coreResp.valid

  for (s <- 0 until numStacks) {
    controllers(s).io.coreResp.ready := io.coreResp.valid && responseHeadStack === s.U
  }

  io.direct.rdata := Mux1H(
    (0 until numStacks).map(s => (directStack === s.U) -> models(s).io.direct.rdata)
  )
}
