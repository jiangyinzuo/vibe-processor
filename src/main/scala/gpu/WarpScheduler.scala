package gpu

import chisel3._
import chisel3.util._

/** Round-Robin Warp 调度器：每周期选择一个活跃（未 halted）的 Warp 执行。
  *
  * 调度策略：
  *   - 使用轮询指针 `ptr` 实现公平调度
  *   - 从 `ptr` 指向的位置开始查找第一个活跃的 Warp
  *   - 授予执行权限后，`ptr` 更新为 (grantIdx + 1) % numWarps
  *   - 下一次调度从下一个 Warp 开始，确保所有活跃 Warp 公平轮换
  *
  * 示例（4 个 Warp，Warp 0 和 3 halted）：
  *   第 1 次调度（ptr=0）：跳过 Warp 0 → 选中 Warp 1 → ptr 更新为 2
  *   第 2 次调度（ptr=2）：选中 Warp 2 → ptr 更新为 3
  *   第 3 次调度（ptr=3）：跳过 Warp 3 → 循环回 Warp 0 → 跳过 Warp 0 → 选中 Warp 1 → ptr 更新为 2
  *
  * 实现技巧：
  *   - 将活跃掩码旋转，使 ptr 位置移到索引 0
  *   - 使用 PriorityEncoder 找到第一个活跃的 Warp
  *   - 将结果映射回原始索引
  */
class WarpScheduler(numWarps: Int = GpuParams.NumWarps) extends Module {
  val io = IO(new Bundle {
    val warpHalted = Input(Vec(numWarps, Bool()))  // 每个 Warp 的 halted 状态
    val grant      = Output(Vec(numWarps, Bool())) // 授予执行权限（one-hot）
    val allHalted  = Output(Bool())                // 所有 Warp 都已 halted
  })

  // 轮询指针：指向下一次调度的起始位置
  // 作用：实现 Round-Robin 公平调度，避免 Warp 饥饿
  val ptr = RegInit(0.U(log2Ceil(numWarps).W))

  // 构建活跃掩码：active(i) = !warpHalted(i)
  // warpHalted 包含两种情况：
  //   1. Warp 已停止（HALT 指令）
  //   2. Warp 正在等待内存（busy = true，协作式让出）
  val active = VecInit((0 until numWarps).map(i => !io.warpHalted(i)))
  io.allHalted := !active.asUInt.orR

  // 旋转活跃掩码：将 ptr 位置移到索引 0
  // rotated(0) = active(ptr), rotated(1) = active(ptr+1), ...
  val rotated = VecInit((0 until numWarps).map(i =>
    active(((i.U +& ptr) % numWarps.U)(log2Ceil(numWarps) - 1, 0))
  ))

  // 优先级编码器：找到旋转后掩码中第一个活跃的 Warp
  val sel = PriorityEncoder(rotated.asUInt)

  // 映射回原始索引：grantIdx = (sel + ptr) % numWarps
  val grantIdx = ((sel +& ptr) % numWarps.U)(log2Ceil(numWarps) - 1, 0)

  // 输出授予信号（one-hot）
  io.grant := VecInit.fill(numWarps)(false.B)
  when(!io.allHalted) {
    io.grant(grantIdx) := true.B
    // 更新轮询指针：指向下一个 Warp
    ptr := ((grantIdx +& 1.U) % numWarps.U)(log2Ceil(numWarps) - 1, 0)
  }
}
