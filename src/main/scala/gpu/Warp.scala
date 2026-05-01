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
    val start   = Input(Bool())   // one-shot start signal
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
  val started = RegInit(false.B)
  when(io.start) { started := true.B }

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
  // 状态机：协作式调度模型
  //   - sRun: 正常执行状态，可以被调度
  //   - sMemWait: 等待内存访问完成，主动让出时间片（协作式）
  //   - sHalted: 已停止，不再参与调度
  val sRun :: sMemWait :: sHalted :: Nil = Enum(3)
  val state = RegInit(sRun)

  // busy 信号：告诉调度器"我在等待内存，请不要调度我"
  // 这是协作式调度的关键：Warp 主动通知调度器自己的状态
  io.busy := state === sMemWait

  // Memory latency counter and data latch
  val memCounter = RegInit(0.U(8.W))
  val memRdLat   = RegInit(0.U(4.W))
  val memDataLat = RegInit(VecInit.fill(warpWidth)(0.S(dw.W)))

  switch(state) {
    is(sRun) {
      when(io.enable && !halted && started) {
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
            // === 协作式调度：访存时主动让出时间片 ===
            // 1. 启动内存读取
            val addr = (regFile(0)(rs1).asUInt + imm12)(GpuParams.GlobalAddrW - 1, 0)
            io.gmemEn   := true.B
            io.gmemAddr := addr

            // 2. 保存读取的数据和目标寄存器（用于延迟写回）
            memDataLat := io.gmemRdata
            memRdLat   := rd

            // 3. 设置延迟计数器（模拟内存访问延迟）
            memCounter := (memLatency - 1).U

            // 4. 进入 sMemWait 状态 → 主动让出时间片
            //    这是协作式调度的核心：Warp 主动进入等待状态
            //    调度器会选择其他活跃的 Warp 执行，实现延迟隐藏
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
      // === 内存等待状态：Warp 让出时间片期间 ===
      // 在这个状态下：
      //   - io.busy = true，调度器不会选择这个 Warp
      //   - 其他 Warp 可以执行，实现延迟隐藏
      //   - 内部计数器递减，模拟内存延迟
      when(memCounter === 0.U) {
        // 延迟结束，写回数据到寄存器
        for (i <- 0 until warpWidth) {
          regFile(i)(memRdLat) := memDataLat(i)
        }
        pc    := pc + 1.U
        // 返回 sRun 状态，重新参与调度
        // io.busy 变为 false，调度器可以再次选择这个 Warp
        state := sRun
      }.otherwise {
        // 继续等待，递减计数器
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
