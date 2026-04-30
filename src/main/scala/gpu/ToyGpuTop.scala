package gpu

import chisel3._
import chisel3.util._

/** Toy GPU Top Level — Multi-SM architecture.
  *
  * @param numSMs      Number of Streaming Multiprocessors (default 4).
  * @param gmemLatency Global memory read latency per warp (default 10).
  */
class ToyGpuTop(
    numSMs:      Int = GpuParams.NumSMs,
    numWarps:    Int = GpuParams.NumWarps,
    warpWidth:   Int = GpuParams.WarpWidth,
    dw:          Int = GpuParams.DataWidth,
    gmemLatency: Int = 10
) extends Module {
  val io = IO(new Bundle {
    val start     = Input(Bool())
    val allHalted = Output(Bool())
    val imemLoadEn   = Input(Bool())
    val imemLoadAddr = Input(UInt(8.W))
    val imemLoadData = Input(UInt(GpuParams.InstrWidth.W))
    val gmemExt = new Bundle {
      val en    = Input(Bool())
      val we    = Input(Bool())
      val addr  = Input(UInt(GpuParams.GlobalAddrW.W))
      val wdata = Input(Vec(warpWidth, SInt(dw.W)))
      val rdata = Output(Vec(warpWidth, SInt(dw.W)))
    }
    // Per-SM performance counters
    val perf = Output(Vec(numSMs, new GpuPerfCounters))
  })

  // Shared instruction memory
  val imem = Mem(GpuParams.IMEMDepth, UInt(GpuParams.InstrWidth.W))
  when(io.imemLoadEn) { imem.write(io.imemLoadAddr, io.imemLoadData) }

  // Shared global memory (combinational read, latency modeled in Warps)
  val gmem = Mem(GpuParams.GlobalDepth, Vec(warpWidth, SInt(dw.W)))

  // External port
  io.gmemExt.rdata := gmem.read(io.gmemExt.addr)
  when(io.gmemExt.en && io.gmemExt.we) {
    gmem.write(io.gmemExt.addr, io.gmemExt.wdata)
  }

  // === Streaming Multiprocessors ===
  val sms = Array.fill(numSMs)(Module(new SM(numWarps, warpWidth, dw, memLatency = gmemLatency)))

  val smHalted = VecInit(sms.map(_.io.allHalted))
  io.allHalted := smHalted.asUInt.andR

  for (i <- 0 until numSMs) {
    val sm = sms(i)
    sm.io.start := io.start

    // Shared instruction memory (all SMs read same program)
    for (w <- 0 until numWarps) {
      sm.io.imemData(w) := imem.read(sm.io.imemAddr(w))
    }

    // Global memory: each SM gets combinational read access
    // Mem supports multiple combinational reads per cycle
    sm.io.gmemRdata := gmem.read(sm.io.gmemAddr)
    when(sm.io.gmemEn && sm.io.gmemWe) {
      gmem.write(sm.io.gmemAddr, sm.io.gmemWdata)
    }
  }

  // === Per-SM Performance Counters ===
  for (i <- 0 until numSMs) {
    val perf = RegInit(0.U.asTypeOf(new GpuPerfCounters))
    io.perf(i) := perf

    val running = RegInit(false.B)
    when(io.start)              { running := true.B }
    when(sms(i).io.allHalted)   { running := false.B }

    when(running && !sms(i).io.allHalted) {
      perf.totalCycles := perf.totalCycles + 1.U
    }
    when(running) {
      for (w <- 0 until numWarps) {
        when(sms(i).io.dbgGrant(w)) {
          perf.activeWarpCycles := perf.activeWarpCycles + 1.U
        }
      }
    }
    when(sms(i).io.gmemEn && !sms(i).io.gmemWe) { perf.gmemReads  := perf.gmemReads  + 1.U }
    when(sms(i).io.gmemEn && sms(i).io.gmemWe)  { perf.gmemWrites := perf.gmemWrites + 1.U }
  }
}

class GpuPerfCounters extends Bundle {
  val totalCycles      = UInt(32.W)
  val activeWarpCycles = UInt(32.W)
  val gmemReads        = UInt(16.W)
  val gmemWrites       = UInt(16.W)
}
