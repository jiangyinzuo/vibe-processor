package ascend

import chisel3._
import chisel3.util._
import common.LatencyMem

/** DMA Engine: transfers N rows between HBM (off-chip) and UB (on-chip).
  *
  * DMA_LOAD (HBM → UB): for each row, issue HBM read → wait for response → write to UB DMA_STORE
  * (UB → HBM): for each row, read from UB → write to HBM (single-cycle write)
  *
  * Controlled by ScalarUnit via start/done handshake.
  */
class DmaEngine(
    n: Int = AscendParams.ArraySize,
    aw: Int = AscendParams.AccWidth,
    addrW: Int = AscendParams.HBMAddrW
) extends Module {
  val io = IO(new Bundle {
    // Control
    val start = Input(Bool())
    val isStore = Input(Bool()) // false = HBM→UB (LOAD), true = UB→HBM (STORE)
    val hbmAddr = Input(UInt(addrW.W)) // HBM base address
    val ubAddr = Input(UInt(AscendParams.UBAddrW.W)) // UB base address
    val done = Output(Bool())
    // UB port
    val ubEn = Output(Bool())
    val ubWe = Output(Bool())
    val ubPortAddr = Output(UInt(AscendParams.UBAddrW.W))
    val ubWdata = Output(Vec(n, SInt(aw.W)))
    val ubRdata = Input(Vec(n, SInt(aw.W)))
    // HBM port (LatencyMem interface)
    val hbmReqValid = Output(Bool())
    val hbmReqReady = Input(Bool())
    val hbmReqWe = Output(Bool())
    val hbmReqAddr = Output(UInt(addrW.W))
    val hbmReqWdata = Output(Vec(n, SInt(aw.W)))
    val hbmRespValid = Input(Bool())
    val hbmRespRdata = Input(Vec(n, SInt(aw.W)))
  })

  val sIdle :: sLoadReq :: sLoadWait :: sLoadWb :: sStoreRd :: sStoreRdWait :: sStoreWr :: sDone :: Nil =
    Enum(8)
  val state = RegInit(sIdle)
  val rowCnt = RegInit(0.U(log2Ceil(n + 1).W))

  // Latched parameters
  val hbmBase = RegInit(0.U(addrW.W))
  val ubBase = RegInit(0.U(AscendParams.UBAddrW.W))
  val isStore = RegInit(false.B)

  // Latched HBM read data
  val hbmDataLat = RegInit(VecInit.fill(n)(0.S(aw.W)))

  // Defaults
  io.done := false.B
  io.ubEn := false.B
  io.ubWe := false.B
  io.ubPortAddr := 0.U
  io.ubWdata := VecInit.fill(n)(0.S(aw.W))
  io.hbmReqValid := false.B
  io.hbmReqWe := false.B
  io.hbmReqAddr := 0.U
  io.hbmReqWdata := VecInit.fill(n)(0.S(aw.W))

  switch(state) {
    is(sIdle) {
      when(io.start) {
        hbmBase := io.hbmAddr
        ubBase := io.ubAddr
        isStore := io.isStore
        rowCnt := 0.U
        state := Mux(io.isStore, sStoreRd, sLoadReq)
      }
    }

    // === DMA_LOAD: HBM → UB ===
    is(sLoadReq) {
      // Issue HBM read request for current row
      io.hbmReqValid := true.B
      io.hbmReqAddr := hbmBase + rowCnt
      when(io.hbmReqReady) {
        state := sLoadWait
      }
    }
    is(sLoadWait) {
      // Wait for HBM response
      when(io.hbmRespValid) {
        hbmDataLat := io.hbmRespRdata
        state := sLoadWb
      }
    }
    is(sLoadWb) {
      // Write to UB
      io.ubEn := true.B
      io.ubWe := true.B
      io.ubPortAddr := ubBase + rowCnt
      io.ubWdata := hbmDataLat
      rowCnt := rowCnt + 1.U
      state := Mux(rowCnt === (n - 1).U, sDone, sLoadReq)
    }

    // === DMA_STORE: UB → HBM ===
    is(sStoreRd) {
      // Issue UB read for current row
      io.ubEn := true.B
      io.ubPortAddr := ubBase + rowCnt
      state := sStoreRdWait
    }
    is(sStoreRdWait) {
      // UB read latency (SyncReadMem: 1 cycle), keep addr and en stable
      io.ubEn := true.B
      io.ubPortAddr := ubBase + rowCnt
      state := sStoreWr
    }
    is(sStoreWr) {
      // Write UB data to HBM (writes are single-cycle in LatencyMem)
      io.ubEn := true.B // keep mux pointed to DMA for rdata
      io.ubPortAddr := ubBase + rowCnt
      io.hbmReqValid := true.B
      io.hbmReqWe := true.B
      io.hbmReqAddr := hbmBase + rowCnt
      io.hbmReqWdata := io.ubRdata
      when(io.hbmReqReady) {
        rowCnt := rowCnt + 1.U
        state := Mux(rowCnt === (n - 1).U, sDone, sStoreRd)
      }
    }

    is(sDone) {
      io.done := true.B
      state := sIdle
    }
  }
}
