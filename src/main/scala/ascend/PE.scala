package ascend

import chisel3._

/** Processing Element for weight-stationary systolic array.
  *
  * - Stores a weight in a register (loaded when `weightLoad` is high)
  * - MAC: psumOut = psumIn + weight * dataIn
  * - Passes dataIn through to dataOut with 1-cycle delay
  */
class PE(dw: Int = AscendParams.DataWidth, aw: Int = AscendParams.AccWidth) extends Module {
  val io = IO(new Bundle {
    val weightLoad = Input(Bool())
    val weightIn   = Input(SInt(dw.W))
    val dataIn     = Input(SInt(dw.W))
    val dataOut    = Output(SInt(dw.W))
    val psumIn     = Input(SInt(aw.W))
    val psumOut    = Output(SInt(aw.W))
  })

  val weightReg = RegInit(0.S(dw.W))

  when(io.weightLoad) {
    weightReg := io.weightIn
  }

  io.dataOut := RegNext(io.dataIn, 0.S(dw.W))
  io.psumOut := RegNext(io.psumIn + weightReg * io.dataIn, 0.S(aw.W))
}
