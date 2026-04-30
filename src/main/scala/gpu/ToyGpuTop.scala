package gpu

import chisel3._
import chisel3.util._

/** Toy GPU Top Level.
  *
  * @param gmemLatency Global memory read latency in cycles (simulated inside each Warp).
  *                    1 = on-chip SRAM speed, 10+ = off-chip DRAM simulation.
  */
class ToyGpuTop(
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
    val perf = Output(new GpuPerfCounters)
  })

  // Instruction memory (on-chip, combinational read)
  val imem = Mem(GpuParams.IMEMDepth, UInt(GpuParams.InstrWidth.W))
  when(io.imemLoadEn) { imem.write(io.imemLoadAddr, io.imemLoadData) }

  // Global memory (single shared Mem, combinational read)
  // Latency is modeled inside each Warp's FSM (stall counter)
  val gmem = Mem(GpuParams.GlobalDepth, Vec(warpWidth, SInt(dw.W)))

  val sm = Module(new SM(numWarps, warpWidth, dw, memLatency = gmemLatency))

  // SM <-> instruction memory
  for (i <- 0 until numWarps) {
    sm.io.imemData(i) := imem.read(sm.io.imemAddr(i))
  }

  // SM <-> global memory (combinational read)
  sm.io.gmemRdata := gmem.read(sm.io.gmemAddr)
  when(sm.io.gmemEn && sm.io.gmemWe) {
    gmem.write(sm.io.gmemAddr, sm.io.gmemWdata)
  }

  // External port (test preload/readback, same backing Mem)
  io.gmemExt.rdata := gmem.read(io.gmemExt.addr)
  when(io.gmemExt.en && io.gmemExt.we) {
    gmem.write(io.gmemExt.addr, io.gmemExt.wdata)
  }

  sm.io.start  := io.start
  io.allHalted := sm.io.allHalted

  // --- Performance Counters ---
  val perf = RegInit(0.U.asTypeOf(new GpuPerfCounters))
  io.perf := perf

  val running = RegInit(false.B)
  when(io.start)     { running := true.B }
  when(io.allHalted) { running := false.B }

  when(running && !io.allHalted) {
    perf.totalCycles := perf.totalCycles + 1.U
  }
  when(running) {
    for (i <- 0 until numWarps) {
      when(sm.io.dbgGrant(i)) {
        perf.activeWarpCycles := perf.activeWarpCycles + 1.U
      }
    }
  }
  when(sm.io.gmemEn && !sm.io.gmemWe) { perf.gmemReads  := perf.gmemReads  + 1.U }
  when(sm.io.gmemEn && sm.io.gmemWe)  { perf.gmemWrites := perf.gmemWrites + 1.U }
}

class GpuPerfCounters extends Bundle {
  val totalCycles      = UInt(32.W)
  val activeWarpCycles = UInt(32.W)
  val gmemReads        = UInt(16.W)
  val gmemWrites       = UInt(16.W)
}
