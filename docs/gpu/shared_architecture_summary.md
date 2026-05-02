# GPU 共享架构重构总结

## 重构动机

**原始设计问题：**
- 每个 Warp 包含独立的 CUDA Core（ALU + 寄存器文件）
- 资源利用率极低：4 Warp × 4 CUDA Core = 16 ALU，但同时只有 1 个 Warp 活跃（利用率 6.25%）
- 与真实 GPU 架构不符

**真实 GPU 架构：**
- CUDA Core 是 SM 级别的共享资源
- Warp 只是轻量级的执行上下文（PC、寄存器状态）
- 调度器从多个 Ready Warp 中选择一个，使用共享的 CUDA Core 执行
- 多个 Warp 时分复用同一组 CUDA Core

## 新架构设计

### 核心模块

#### 1. WarpContext（轻量级执行上下文）
```scala
class WarpContext(warpWidth: Int) extends Bundle {
  val state = WarpState()           // Idle/Ready/WaitMem/Halted
  val pc = UInt(32.W)               // 程序计数器
  val memWaitCounter = UInt(8.W)   // 内存等待计数器
  val memRdData = Vec(warpWidth, UInt(32.W))  // 内存读取数据缓冲
}
```

- 不包含寄存器文件和 ALU，只保存执行状态和控制信息
- 内存数据缓冲解决组合逻辑读取问题

#### 2. SharedRegisterFile（共享寄存器文件）
```scala
class SharedRegisterFile(numRegs: Int, warpWidth: Int, numWarps: Int) extends Module {
  val regs = Reg(Vec(numWarps, Vec(numRegs, Vec(warpWidth, UInt(32.W)))))
  // 多端口访问：2个读端口 + warpWidth个写端口
}
```

- 所有 Warp 的寄存器集中管理
- 支持多端口并行写回（内存写回 + 算术指令写回）
- 通过 warpId 索引访问对应 Warp 的寄存器

#### 3. InstructionDispatcher（指令分发器）
- 从选中的 Warp 读取指令并解码
- 单周期执行算术/逻辑指令
- 内存指令设置等待状态，延迟写回
- 检测写端口冲突，避免同时写回

#### 4. SMSubPartition（SM 内部分区）
- 每个分区包含 1 个 WarpScheduler
- 每个分区管理一组本地 WarpContext
- 每个分区拥有一组 lane 执行单元（CudaCore + SFU）
- 分区输出一个被选中的全局 warpId，交给 SM 顶层的 InstructionDispatcher

#### 5. SM（共享架构 SM）
**执行流程：**
1. 每个 SMSubPartition 的 Round-robin 调度器选择 Ready 状态的 Warp
2. Dispatcher 读取指令并执行
3. 从 SharedRegisterFile 读取操作数
4. 使用对应 sub-partition 的 CudaCore/SFU 执行运算
5. 发起全局内存读写请求
6. 结果写回 SharedRegisterFile

### 关键设计决策

#### 1. 内存数据缓冲
**问题：** 连续 LOAD 指令互相覆盖数据（内存读取使用组合逻辑，写回时地址已变化）

**解决：** 在 WarpContext 中添加 `memRdData` 缓冲，内存请求发起时立即锁存数据

#### 2. 写端口冲突处理
**问题：** 内存写回和算术指令写回可能同时发生

**解决：** 当有内存写回时，禁止 Dispatcher 发射新指令

#### 3. 寄存器读取时机
**问题：** LOAD/STORE 指令需要先读取 rs1 寄存器获取地址

**解决：** 在 SM 中提前读取寄存器，传递给 Dispatcher

## 性能对比

### 资源利用率
| 架构 | CUDA Core 数量 | 活跃 Warp | 利用率 |
|------|---------------|-----------|--------|
| 原始（独立） | 16 (4 Warp × 4 Core) | 1 | 6.25% |
| 新架构（2 分区） | 8 (2 × 4 Core) | 1 | 50% |
| 理论最大 | 8 (2 × 4 Core) | 2 | 100% |

### 测试结果
**4-SM 向量加法：**
- 执行周期：14 cycles
- 结果：[11, 22, 33, 44] ✅
- SM 利用率：85.7% (12/14 周期活跃)

**测试通过率：16/16 (100%)**
- GpuIntegrationTest (3/3)
- DualSchedulerTest (4/4)
- SharedArchDebug (3/3)
- QuickSharedArchTest (1/1)
- CudaCoreTest (5/5)

## 代码变更

### 共享架构保留文件
- `src/main/scala/gpu/WarpContext.scala`
- `src/main/scala/gpu/SharedRegisterFile.scala`
- `src/main/scala/gpu/InstructionDispatcher.scala`
- `src/main/scala/gpu/SMSubPartition.scala`
- `src/main/scala/gpu/SM.scala`
- `src/test/scala/gpu/SharedArchDebug.scala`
- `src/test/scala/gpu/QuickSharedArchTest.scala`

### 修改文件
- `src/main/scala/gpu/ToyGpuTop.scala` - 使用共享架构 SM

### 移除旧实现
- 旧版每 Warp 独占执行单元模块
- 旧版双架构兼容接口

## 总结

成功将玩具 GPU 从"每个 Warp 独立 CUDA Core"的错误设计，改为"共享 CUDA Core + 轻量级 Warp 上下文"的正确架构：

- ✅ 符合真实 GPU 设计原则
- ✅ 资源利用率从 6.25% 提升到 25%（单 Warp）/ 100%（理论最大）
- ✅ 所有测试通过，功能正确
- ✅ 性能计数器正确统计周期和内存访问

为后续实现更复杂的 GPU 特性（多 Warp 并发、分支处理、共享内存）奠定了坚实基础。
