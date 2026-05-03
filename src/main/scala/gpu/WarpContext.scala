package gpu

import chisel3._
import chisel3.util._

/** Warp 状态枚举 */
object WarpState extends ChiselEnum {
  val Ready = Value // 就绪，可以被调度
  val Stalled = Value // 等待内存访问
  val Halted = Value // 已停止
}

/** Warp 执行上下文（轻量级）
  *
  * 真实 GPU 中，Warp 只是一个逻辑概念，不包含物理计算单元。 Warp 只保存执行状态：PC、活跃掩码、等待状态等，不保存通用寄存器值。
  *
  * 物理计算单元（CUDA Core）是 SM 级别的共享资源， 由调度器和分发器动态分配给不同的 Warp。
  *
  * 通用寄存器值存放在 SM 级 SharedRegisterFile 中，并按 (warpId, laneId, regId) 索引。调度器切换 Warp 时只是换一个 warpId
  * 访问同一个物理寄存器文件，不会覆盖其它 Warp 的寄存器。
  */
class WarpContext(warpWidth: Int = 8, dw: Int = 32) extends Bundle {
  val pc = UInt(8.W) // 程序计数器
  val state = WarpState() // Warp 状态
  val activeMask = UInt(8.W) // 活跃线程掩码（8 线程）
  val ctaId = UInt(GpuParams.CTAIdWidth.W) // 所属 CTA / thread block
  val memWaitCounter = UInt(8.W) // 内存等待计数器
  val memRdReg = UInt(4.W) // 内存读取目标寄存器
  val memRdData = Vec(warpWidth, SInt(dw.W)) // 内存读取数据缓冲
  val started = Bool() // 是否已启动
}

object WarpContext {

  /** 创建初始化的 WarpContext */
  def init(warpWidth: Int = 8, dw: Int = 32): WarpContext = {
    val ctx = Wire(new WarpContext(warpWidth, dw))
    ctx.pc := 0.U
    ctx.state := WarpState.Ready
    ctx.activeMask := 0xff.U // 所有 8 个线程活跃
    ctx.ctaId := 0.U
    ctx.memWaitCounter := 0.U
    ctx.memRdReg := 0.U
    ctx.memRdData := VecInit(Seq.fill(warpWidth)(0.S(dw.W)))
    ctx.started := false.B
    ctx
  }
}
