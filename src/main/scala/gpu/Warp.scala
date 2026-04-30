package gpu

import chisel3._
import chisel3.util._

/** A Warp: SIMT execution of WarpWidth threads sharing one PC.
  *
  * Instruction format (32-bit):
  *   [31:28] opcode  [27:24] rd  [23:20] rs1  [19:16] rs2  [15:12] rs3  [11:0] imm12
  *
  * Memory model: LD takes `memLatency` cycles (warp stalls in sMemWait).
  * The latency counter is internal — no external handshake needed.
  */
class Warp(
    warpWidth:  Int = GpuParams.WarpWidth,
    numRegs:    Int = GpuParams.NumRegs,
    dw:         Int = GpuParams.DataWidth,
    memLatency: Int = 1
) extends Module {
  val io = IO(new Bundle {
    val enable  = Input(Bool())
    val halted  = Output(Bool())
    val busy    = Output(Bool())  // in sMemWait, don't schedule me
    val pc      = Output(UInt(8.W))
    val instr   = Input(UInt(GpuParams.InstrWidth.W))
    // Global memory (combinational read from shared Mem)
    val gmemEn    = Output(Bool())
    val gmemWe    = Output(Bool())
    val gmemAddr  = Output(UInt(GpuParams.GlobalAddrW.W))
    val gmemWdata = Output(Vec(warpWidth, SInt(dw.W)))
    val gmemRdata = Input(Vec(warpWidth, SInt(dw.W)))
    // Shared memory (on-chip, single-cycle)
    val shmEn    = Output(Bool())
    val shmWe    = Output(Bool())
    val shmAddr  = Output(UInt(GpuParams.SharedAddrW.W))
    val shmWdata = Output(Vec(warpWidth, SInt(dw.W)))
    val shmRdata = Input(Vec(warpWidth, SInt(dw.W)))
  })

  val regFile = RegInit(VecInit.fill(warpWidth, numRegs)(0.S(dw.W)))
  val pc      = RegInit(0.U(8.W))
  val halted  = RegInit(false.B)

  io.pc     := pc
  io.halted := halted

  val instr = io.instr
  val op    = instr(31, 28)
  val rd    = instr(27, 24)
  val rs1   = instr(23, 20)
  val rs2   = instr(19, 16)
  val rs3   = instr(15, 12)
  val imm12 = instr(11, 0)

  val cores = Array.fill(warpWidth)(Module(new CudaCore(dw)))

  // Defaults
  io.gmemEn    := false.B
  io.gmemWe    := false.B
  io.gmemAddr  := 0.U
  io.gmemWdata := VecInit.fill(warpWidth)(0.S(dw.W))
  io.shmEn     := false.B
  io.shmWe     := false.B
  io.shmAddr   := 0.U
  io.shmWdata  := VecInit.fill(warpWidth)(0.S(dw.W))

  for (i <- 0 until warpWidth) {
    cores(i).io.valid := false.B
    cores(i).io.op    := 0.U
    cores(i).io.rs1   := 0.S
    cores(i).io.rs2   := 0.S
    cores(i).io.rs3   := 0.S
  }

  // FSM: sRun is the normal state, sMemWait stalls for memory latency
  val sRun :: sMemWait :: sHalted :: Nil = Enum(3)
  val state = RegInit(sRun)
  io.busy := state === sMemWait

  // Memory latency counter and data latch
  val memCounter = RegInit(0.U(8.W))
  val memRdLat   = RegInit(0.U(4.W))
  val memDataLat = RegInit(VecInit.fill(warpWidth)(0.S(dw.W)))

  switch(state) {
    is(sRun) {
      when(io.enable && !halted) {
        switch(op) {
          is(GpuOpcode.NOP)  { pc := pc + 1.U }
          is(GpuOpcode.HALT) { halted := true.B; state := sHalted }
          is(GpuOpcode.ADD, GpuOpcode.MUL, GpuOpcode.MAD) {
            for (i <- 0 until warpWidth) {
              cores(i).io.valid := true.B
              cores(i).io.op    := op
              cores(i).io.rs1   := regFile(i)(rs1)
              cores(i).io.rs2   := regFile(i)(rs2)
              cores(i).io.rs3   := regFile(i)(rs3)
            }
            pc := pc + 1.U
          }
          is(GpuOpcode.LD) {
            val addr = (regFile(0)(rs1).asUInt + imm12)(GpuParams.GlobalAddrW - 1, 0)
            io.gmemEn   := true.B
            io.gmemAddr := addr
            memDataLat := io.gmemRdata
            memRdLat   := rd
            memCounter := (memLatency - 1).U
            state      := sMemWait
          }
          is(GpuOpcode.ST) {
            io.gmemEn   := true.B
            io.gmemWe   := true.B
            io.gmemAddr := (regFile(0)(rs1).asUInt + imm12)(GpuParams.GlobalAddrW - 1, 0)
            for (i <- 0 until warpWidth) { io.gmemWdata(i) := regFile(i)(rs2) }
            pc := pc + 1.U
          }
          is(GpuOpcode.SHM) {
            io.shmEn   := true.B
            io.shmWe   := imm12(11)
            io.shmAddr := regFile(0)(rs1).asUInt(GpuParams.SharedAddrW - 1, 0)
            when(imm12(11)) {
              for (i <- 0 until warpWidth) { io.shmWdata(i) := regFile(i)(rs2) }
            }.otherwise {
              for (i <- 0 until warpWidth) { regFile(i)(rd) := io.shmRdata(i) }
            }
            pc := pc + 1.U
          }
        }
      }
    }
    is(sMemWait) {
      when(memCounter === 0.U) {
        for (i <- 0 until warpWidth) {
          regFile(i)(memRdLat) := memDataLat(i)
        }
        pc    := pc + 1.U
        state := sRun
      }.otherwise {
        memCounter := memCounter - 1.U
      }
    }
    is(sHalted) { /* stay */ }
  }

  // CudaCore writeback (1-cycle latency)
  val wbRd = RegNext(rd)
  for (i <- 0 until warpWidth) {
    when(cores(i).io.done) {
      regFile(i)(wbRd) := cores(i).io.rd
    }
  }
}
