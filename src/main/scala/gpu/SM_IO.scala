package gpu

import chisel3._
import chisel3.util._

/** SM 的统一接口
  *
  * 为了支持新旧两种架构，定义统一的 IO 接口
  */
class SM_IO(numWarps: Int, warpWidth: Int, dw: Int) extends Bundle {
  val start     = Input(Bool())
  val allHalted = Output(Bool())
  val imemAddr  = Output(Vec(numWarps, UInt(8.W)))
  val imemData  = Input(Vec(numWarps, UInt(GpuParams.InstrWidth.W)))
  val gmemEn    = Output(Bool())
  val gmemWe    = Output(Bool())
  val gmemAddr  = Output(UInt(GpuParams.GlobalAddrW.W))
  val gmemWdata = Output(Vec(warpWidth, SInt(dw.W)))
  val gmemRdata = Input(Vec(warpWidth, SInt(dw.W)))
  val dbgGrant  = Output(Vec(numWarps, Bool()))
}

/** SM 基类 trait */
trait SM_Base {
  def io: SM_IO
}
