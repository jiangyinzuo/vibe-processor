# 玩具GPU整体架构

## 线程层次结构

thread → warp → block/CTA → grid

### Warp: 最小执行单位

Warp体现了GPU的SIMT编程模型，它具有以下好处：
1. 指令调度成本低。32 个 thread 共用一条指令流，大幅减少调度器复杂度。
2. 执行单元利用率高。多个 lane 可以一起做加法、乘法、load/store、tensor core 相关操作。
3. 内存访问可以合并。如果一个 warp 里的 thread 访问连续地址，硬件可以把很多小访问合并成更高效的 memory transaction。
4. 隐藏延迟。一个 warp 等内存时，SM 可以切到另一个 ready warp。GPU 不是靠 CPU 那种大 cache + 乱序执行来掩盖延迟，而是靠 海量 warp 轮换。

实现

- Warp
- WarpContext：保存 PC、Ready/Stalled/Halted 状态、访存等待状态。
- Warp Scheduler：每个 SM 2 个 scheduler，每个 scheduler 管理一组 warp。
    - Round-robin warp 调度：跳过 halted/stalled warp，用 ready warp 隐藏访存延迟。

### Thread Block / CTA: 片上内存共享

### Grid: 跨SM协作

- 一个GPU包含多个SM，每个SM可以驻留多个resident CTA。
- 线程块调度：CTAScheduler
- SM Subpartition：每个 SM 分成 2 个 SMSubPartition。

## 存储层次结构

### 寄存器

- 共享寄存器文件：SharedRegisterFile 按 (warpId, laneId, regId) 索引。
- threadIdx.x / blockIdx.x 特殊寄存器约定：R12 = threadIdx.x，R14 = blockIdx.x。

### Global Memory

- Global Memory LD/ST：支持 warp 级 LD / ST。
- HBM-backed GlobalMem：当前接到 HbmStackedMemory，默认 4 个 HBM stack。
- HBM controller 时序模型：stack 内有 channel/bank/row 解码、row hit/miss、bank busy、请求队列。
- 访存 backpressure：HBM 请求未 ready 时会阻塞发射。

## 计算单元

### CudaCore

- 共享 CUDA Core 架构：CUDA Core 属于 SM/subpartition，不再属于单个 warp。
- 整数 CUDA Core 指令：ADD、MUL、MAD。
- SFU：支持 EXP，使用 Q16.16 定点、查找表和线性插值。
- ALU / SFU 分离发射路径：已有 ALU 与 SFU 并行发射计数 dualIssueCycles。
- Instruction Dispatcher：负责取指、解码、寄存器读、发射、写回、访存请求。

---

有模型但还不完整

- SharedMem 存储体和 SHM opcode 已存在，但当前没有完整 shared-memory 指令路径。
- 有 CTA/thread/block ID，但只支持 1D。
- 有双 scheduler，但没有真实 NVIDIA 那种完整 scoreboard、operand collector、issue port 细节。

尚未实现

- Tensor Core / MMA 指令。
- Warp divergence、active mask、predicate。
- Branch 指令和 reconvergence。
- L1/L2 cache、coalescer、memory partition 完整模型。
- shared memory bank conflict。
- atomics、barrier、syncwarp、CTA barrier。
- PTX/SASS 级真实指令集。
- 真实 occupancy/resource 限制模型。

