# 玩具版英伟达 GPU 架构文档

## 概述

玩具版英伟达 GPU：Grid/CTA/Warp 层次 + 4×SM，共享 CUDA Core 架构 + Warp 调度 + 可配置存储延迟

使用 Chisel 7 (Scala) 编写，Verilator (via svsim) 仿真，ScalaTest 验证。

**[交互式架构图](../interactive/index.html)** - 可视化 GPU 架构，支持模块导航和 Warp 调度动画

---

## 1. 多 SM 架构

![GPU 架构图](../diagrams/gpu_architecture.svg)

- 4 个 SM 并行执行同一个 kernel grid
- 顶层 `CTAScheduler` 将 grid 中的 CTA/thread block 分配到 SM 的 resident CTA slot
- 默认 grid 有 8 个 CTA；每个 SM 有 2 个 resident CTA slot (`MaxCTAsPerSM = 2`)
- 每个 CTA 包含 2 个 Warp；每个 Warp 4 条 lane，因此每个 CTA 有 8 个线程
- 每个 SM 内仍保留 4 个轻量级 WarpContext，即 2 CTA × 2 Warp/CTA
- 每个 SM 显式拆成 2 个 SMSubPartition，每个分区管理 2 个 WarpContext、1 个 WarpScheduler 和 1 组 lane 执行单元
- SharedRegisterFile、SharedMem 和访存请求路径仍是 SM 级共享资源，不再保留每 Warp 独占执行单元的旧实现
- 共享 HBM-backed GlobalMem 和 InstrMem，每个 SM 有私有 SharedMem

---

## 2. CTA / Thread Block 层

真实 CUDA 程序使用 `threadIdx`、`blockIdx`、`blockDim`、`gridDim` 这些 device-only built-in variables 获取线程块和线程坐标。NVIDIA CUDA Programming Guide 中说明：线程先组织成 thread block，再组织成 grid；kernel 内可以查询 `blockIdx` 和 `threadIdx`，常见线性下标是 `threadIdx.x + blockIdx.x * blockDim.x`。参考：[CUDA SIMT thread hierarchy](https://docs.nvidia.cn/cuda/cuda-programming-guide/02-basics/writing-cuda-kernels.html#thread-hierarchy) 和 [Built-in Variables](https://docs.nvidia.cn/cuda/cuda-programming-guide/05-appendices/cpp-language-extensions.html#built-in-variables)。

本项目实现的是 1D 简化版本：

| 参数 | 默认值 | 含义 |
|------|--------|------|
| `NumCTAs` | 8 | grid 中的 CTA/thread block 数 |
| `MaxCTAsPerSM` | 2 | 每个 SM 同时驻留的 CTA 数 |
| `WarpsPerCTA` | 2 | 每个 CTA 的 Warp 数 |
| `WarpWidth` | 4 | 每个 Warp 的 lane/thread 数 |
| `ThreadsPerCTA` | 8 | 每个 CTA 的线程数 |

执行流程：

1. `ToyGpuTop.io.start` 拉高时，`CTAScheduler` 从 CTA 0 开始分配。
2. 每个 SM 暴露 2 个 resident CTA slot；空 slot 会被填入一个 CTA ID。
3. 一个 CTA slot 固定拥有 2 个物理 WarpContext。
4. 该 CTA 的所有 Warp 执行到 `HALT` 后，SM 上报 `ctaDone`。
5. `CTAScheduler` 继续把后续 CTA 分配到空 slot，直到所有 CTA 完成。

### 特殊寄存器约定

真实 GPU 中这些坐标由编译器/硬件通过内建变量和特殊寄存器路径提供；本项目用保留寄存器显式暴露给玩具 ISA：

| 寄存器 | 含义 |
|--------|------|
| `R12` | `threadIdx.x`，CTA 内线程号 |
| `R13` | `warpIdxInCTA`，CTA 内 Warp 号 |
| `R14` | `blockIdx.x` / CTA ID |
| `R15` | 固定为 0，保持旧测试中零基址用法 |

示例：计算 1D 全局线程号。

```asm
LD   R0, [R15 + 0]     ; R0 = blockDim.x，本项目测试中预置为 8
MUL  R1, R14, R0       ; R1 = blockIdx.x * blockDim.x
ADD  R2, R1, R12       ; R2 = global thread id
```

当前限制：

- 只实现 1D `threadIdx.x` / `blockIdx.x`。
- `blockDim.x` 还不是硬件特殊寄存器，示例测试通过 HBM-backed GlobalMem 常量加载。
- 尚未实现 CTA 级 barrier、每 CTA shared memory 分区、block-level synchronization。

---

## 3. SIMT 执行模型

- **WarpContext** = 4 条 lane（线程）的轻量级执行上下文，保存 PC/状态/访存等待信息
- **SMSubPartition**：每个分区包含 1 个 WarpScheduler、4 个 CudaCore、4 个 SFU
- **双 WarpScheduler**：2 个分区各有 1 个独立调度器，每周期最多并行发射 2 个 Warp
- **分区内 CudaCore**：单周期 ALU (ADD/MUL/MAD)，由 Dispatcher 映射到对应分区的 lane group
- **分区内 SFU**：特殊函数单元，支持 EXP
- **SharedRegisterFile**：按 `(warpId, laneId, regId)` 索引的 SM 级共享寄存器文件
- **延迟隐藏**：Warp 在 LD 等待期间，scheduler 调度其他 Warp

### Warp 切换与寄存器文件

Warp 切换不会覆盖寄存器值，因为寄存器文件不是只按 `regId` 索引，而是按三元组索引：

```text
regs[warpId][laneId][regId]
```

例如：

```text
regs[0][0][R1] != regs[1][0][R1]
regs[0][3][R2] != regs[1][3][R2]
```

所以从 `warp 0` 切换到 `warp 1` 时，调度器并不保存/恢复寄存器，只是让 dispatcher 在读写 `SharedRegisterFile` 时带上新的 `warpId`。`warp 0` 的 `R1` 和 `warp 1` 的 `R1` 物理上就是不同槽位。

这也是 GPU 能快速切换 warp 来隐藏访存延迟的关键之一：resident warp 的寄存器长期驻留在 SM 片上寄存器文件里，切换时只需要改变调度选择、PC/active mask/scoreboard 等控制状态。

### 双调度器架构

```
每个 SM:
  - 2 个 SMSubPartition
  - SubPartition 0: Scheduler 0 管理 Warp 0, 1，拥有 lane 0..3 执行单元
  - SubPartition 1: Scheduler 1 管理 Warp 2, 3，拥有 lane 4..7 执行单元
  - 每周期可并行执行 2 个 Warp（如果资源允许）
  - 资源仲裁：HBM Controller 和 SharedMem 优先级仲裁
```

**性能观察**：
- 纯计算程序：`stalledWarpCycles=0`、`noEligibleCycles=0`
- 内存密集程序：`stalledWarpCycles=88`、`noEligibleCycles=14`，说明访存等待被部分隐藏

详见 [dual_scheduler_summary.md](dual_scheduler_summary.md)

### WarpScheduler 调度策略

**Round-Robin 轮询调度**：使用轮询指针 `ptr` 实现公平的 Warp 调度

**工作原理**：
1. **轮询指针 `ptr`**：指向下一次调度的起始位置（初始值为 0）
2. **查找活跃 Warp**：从 `ptr` 位置开始，查找第一个活跃（未 halted）的 Warp
3. **授予执行权限**：选中的 Warp 获得当前周期的执行权限
4. **更新指针**：`ptr` 更新为 `(grantIdx + 1) % numWarps`，下次从下一个 Warp 开始

**调度示例**（4 个 Warp，Warp 0 和 3 已 halted）：
```
第 1 次调度（ptr=0）：跳过 Warp 0 → 选中 Warp 1 → ptr 更新为 2
第 2 次调度（ptr=2）：选中 Warp 2 → ptr 更新为 3
第 3 次调度（ptr=3）：跳过 Warp 3 → 循环回 Warp 0 → 跳过 Warp 0 → 选中 Warp 1 → ptr 更新为 2
```

**优势**：
- **公平性**：确保每个活跃 Warp 都有平等的执行机会，避免 Warp 饥饿
- **简单高效**：只需一个寄存器和简单的旋转逻辑，硬件开销小
- **延迟隐藏**：当一个 Warp 等待内存时，调度器可以切换到其他 Warp 执行

详见 [warp_scheduling.md](warp_scheduling.md)

### 协作式调度机制

**Warp 主动让出时间片**：

```scala
// WarpContext 在访存时进入 Stalled 状态
is(GpuOpcode.LD) {
  state := WarpState.Stalled  // 主动让出时间片
}

// SubPartition 内调度器跳过 Halted/Stalled 的 Warp
scheduler.io.warpHalted(w) :=
  warpState(w) === WarpState.Halted || warpState(w) === WarpState.Stalled
```

**关键特点**：
- Warp **主动**让出时间片（协作式）
- 调度器**不会强制抢占**
- 在访存时切换，实现延迟隐藏

详见 [warp_scheduling.md](warp_scheduling.md#协作式调度)

---

## 4. 存储层次

| 层级 | 类型 | 深度 | 延迟 | 共享范围 |
|------|------|------|------|----------|
| HBM-backed GlobalMem | HbmController + HbmModel | 4096 | HBM controller row timing + 1-cycle storage backend | 全局 (4 SM 共享) |
| SharedMem (per-SM) | SyncReadMem | 256 | 1 cycle | SM 内 |
| SharedRegisterFile | Reg | 4 Warp × 4 Lane × 16 Reg | 0 | SM 内 |

真实 HBM 内部还有 stack、channel/pseudo-channel、bank/bank group 和 row buffer 等层次。当前 GPU 模型中的 `HbmController` 已经包含简化的 channel/bank/row 时序，但仍没有建模真实 channel 级并行、bank group 争用、刷新和 ECC。`HbmModel` 只负责存储后端。详见 [HBM 真实结构与控制器职责](../hbm_architecture.md)。

---

## 5. 指令集 (9 条)

详见 [isa.md](../isa.md#gpu-指令集)

格式：`[31:28]op [27:24]rd [23:20]rs1 [19:16]rs2 [15:12]rs3 [11:0]imm12`

| 操作码 | 助记符 | 功能 |
|--------|--------|------|
| 0x0 | NOP | 空操作 |
| 0x1 | HALT | 停机 (当前 Warp) |
| 0x2 | LD | Rd = HBM-backed GlobalMem[Rs1 + imm] |
| 0x3 | ST | HBM-backed GlobalMem[Rs1 + imm] = Rs2 |
| 0x4 | ADD | Rd = Rs1 + Rs2 |
| 0x5 | MUL | Rd = Rs1 × Rs2 |
| 0x6 | MAD | Rd = Rs1 × Rs2 + Rs3 |
| 0x7 | SHM | SharedMem 操作 |
| 0x8 | EXP | Rd = e^Rs1 |

---

## 6. 性能数据

### 向量加法 (latency=1)

```
程序：LD×2 → ADD → ST → HALT
4 SM 并行

性能：
  总周期：143
  Live Warp 周期：423
  Eligible Warp 周期：147
  Stalled Warp 周期：276
  No-eligible 周期：42
  说明：4 个 SM 共享一个 HBM Controller；这里报告最后完成的 SM 计数
```

### 向量加法 (latency=10)

```
程序：LD×2 → ADD → ST → HALT
4 SM 并行

性能：
  总周期：259
  Live Warp 周期：820
  Eligible Warp 周期：241
  Stalled Warp 周期：579
  No-eligible 周期：83
  说明：HBM Controller 的 row/bank 时序和全局排队共同拉长执行时间
```

### CTA/thread/block ID 验证

```
测试：1 SM，4 CTA，MaxCTAsPerSM=2，WarpsPerCTA=2
程序：读取 R12/R14，计算 blockIdx.x * blockDim.x + threadIdx.x，并写回 HBM-backed GlobalMem
结果：113 cycles，ctaLaunches=4，ctaCompletions=4
验证：每个 CTA 写回 threadIdx=[0,1,2,3] 和 [4,5,6,7]，blockIdx 与 CTA ID 一致
```

### 调度延迟隐藏指标

以下为 `gpu.DualSchedulerTest` 中单 SM 的性能计数；该测试已经把 global memory 接到 HBM Controller + HBM Model。

| 测试场景 | total | eligible | stalled | no-eligible | 结论 |
|---------|------:|---------:|--------:|------------:|------|
| **纯计算（10条ADD）** | 85 | 252 | 0 | 0 | 无访存等待 |
| **内存密集（latency=10）** | 110 | 126 | 205 | 22 | 访存等待与 HBM 争用同时存在 |
| **混合程序（latency=5）** | 77 | 226 | 50 | 0 | 大多数等待被覆盖 |

---

## 7. 与真实 GPU 的对比

详见 [warp_scheduling.md](warp_scheduling.md#与真实-gpu-对比)

| 维度 | 玩具 GPU | 真实 GPU (Ampere) | 差距 |
|------|---------|------------------|------|
| **Scheduler 数量** | 2 个 | 4 个 | 2× |
| **每周期指令** | 2 条 | 4 条 | 2× |
| **每 SM Warp 数** | 4 个 | 64 个 | 16× |
| **每 SM resident CTA** | 2 个 | 受架构和资源限制，常见上限 16-32 个 | 8-16× |
| **线程/Block 坐标** | R12/R14 特殊寄存器约定 | `threadIdx` / `blockIdx` built-in variables | 只实现 1D |
| **执行单元** | 共享 CudaCore | 分区 + 多类型单元 | - |
| **调度策略** | Round-Robin | Round-Robin + 优先级 | - |

**总体差距**：约 32× 并行度

---

## 8. 测试

```bash
# GPU 单元测试
sbt "testOnly gpu.CudaCoreTest"

# GPU 集成测试
sbt "testOnly gpu.GpuIntegrationTest"

# 双调度器性能测试
sbt "testOnly gpu.DualSchedulerTest"

# 所有 GPU 测试
sbt "testOnly gpu.*"
```

---

## 9. 源代码

```
src/main/scala/gpu/
├── GpuParams.scala         # GPU 参数配置
├── CTAScheduler.scala      # Grid CTA/thread-block 调度器
├── CudaCore.scala          # CUDA Core (ADD/MUL/MAD)
├── SFU.scala               # Special Function Unit (EXP)
├── SMSubPartition.scala    # SM 内部分区：scheduler + lane 执行单元
├── WarpContext.scala       # 轻量级 Warp 执行上下文
├── WarpScheduler.scala     # Round-Robin 调度器
├── SharedRegisterFile.scala  # SM 级共享寄存器文件
├── InstructionDispatcher.scala # 指令分发、访存请求、写回
├── SM.scala                # 唯一保留的 SM 实现（共享架构）
└── ToyGpuTop.scala         # 顶层 (4×SM + HBM-backed GlobalMem)
```

---

## 相关文档

- [Warp 调度详解](warp_scheduling.md) - 调度算法、协作式调度、延迟隐藏
- [双调度器总结](dual_scheduler_summary.md) - 双调度器实现和性能提升
- [性能对比](performance_comparison.md) - NPU vs GPU 性能分析
- [指令集](../isa.md) - 详细指令说明
- [主文档](../README.md) - 返回文档索引
