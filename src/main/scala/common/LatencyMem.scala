package common

import chisel3._
import chisel3.util._

/** Memory with configurable read latency, simulating off-chip DRAM behavior.
  *
  * - latency=1: behaves like on-chip SRAM (SyncReadMem equivalent)
  * - latency=N: request takes N cycles to return data (models DRAM/HBM)
  *
  * Interface uses valid/ready handshake:
  *   - Requester asserts req.valid + addr (+ wdata/we for writes)
  *   - LatencyMem asserts req.ready when it can accept a new request
  *   - After `latency` cycles, resp.valid goes high with read data
  *
  * Writes complete silently (no response needed).
  */
class LatencyMem[T <: Data](
    gen:     T,
    depth:   Int,
    latency: Int = 1,
    addrW:   Int = 16
) extends Module {
  require(latency >= 1, "latency must be >= 1")

  val io = IO(new Bundle {
    // Request port (with latency)
    val req = new Bundle {
      val valid = Input(Bool())
      val ready = Output(Bool())
      val we    = Input(Bool())
      val addr  = Input(UInt(addrW.W))
      val wdata = Input(gen)
    }
    // Response port (read data)
    val resp = new Bundle {
      val valid = Output(Bool())
      val rdata = Output(gen)
    }
    // Direct access port (no latency, for test preload/readback)
    val direct = new Bundle {
      val en    = Input(Bool())
      val we    = Input(Bool())
      val addr  = Input(UInt(addrW.W))
      val wdata = Input(gen)
      val rdata = Output(gen)
    }
  })

  val mem = Mem(depth, gen)

  if (latency == 1) {
    // Fast path: single-cycle, always ready
    io.req.ready  := true.B
    io.resp.valid := RegNext(io.req.valid && !io.req.we, false.B)
    io.resp.rdata := RegNext(mem.read(io.req.addr))

    when(io.req.valid && io.req.we) {
      mem.write(io.req.addr, io.req.wdata)
    }
  } else {
    // Slow path: FSM with counter to model multi-cycle latency
    val sIdle :: sBusy :: Nil = Enum(2)
    val state   = RegInit(sIdle)
    val counter = RegInit(0.U(log2Ceil(latency + 1).W))
    val isRead  = RegInit(false.B)
    val rdataReg = Reg(gen)

    io.req.ready  := state === sIdle
    io.resp.valid := false.B
    io.resp.rdata := rdataReg

    switch(state) {
      is(sIdle) {
        when(io.req.valid) {
          when(io.req.we) {
            // Writes complete immediately (pipelined write)
            mem.write(io.req.addr, io.req.wdata)
          }.otherwise {
            // Reads take `latency` cycles
            rdataReg := mem.read(io.req.addr)
            isRead   := true.B
            counter  := (latency - 1).U
            state    := sBusy
          }
        }
      }
      is(sBusy) {
        when(counter === 0.U) {
          io.resp.valid := true.B
          state         := sIdle
        }.otherwise {
          counter := counter - 1.U
        }
      }
    }
  }

  // Direct access port (combinational read, synchronous write, no latency)
  io.direct.rdata := mem.read(io.direct.addr)
  when(io.direct.en && io.direct.we) {
    mem.write(io.direct.addr, io.direct.wdata)
  }
}
