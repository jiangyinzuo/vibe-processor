# 玩具版英伟达 GPU 架构文档

## 概述

玩具版英伟达 GPU：4×SM，SIMT 执行模型 + Warp 调度 + 可配置存储延迟

使用 Chisel 7 (Scala) 编写，Verilator (via svsim) 仿真，ScalaTest 验证。

**[🔗 交互式架构图](../interactive/index.html)** - 可视化 GPU 架构，支持模块导航和 Warp 调度动画

---

## 1. 多 SM 架构

![GPU 架构图](../diagrams/gpu_architecture.svg)

- 4 个 SM 并行执行相同程序
- 每个 SM 内 4 个 Warp (每 Warp 4 线程)，**双调度器** Round-Robin 调度
- 共享 GlobalMem 和 InstrMem，每个 SM 有私有 SharedMem

---

## 2. SIMT 执行模型

- **Warp** = 4 条 lane（线程），共享 PC，SIMT 并行执行
- **双 WarpScheduler**：2 个独立调度器，每周期可并行执行 2 个 Warp
- **CudaCore**：单周期 ALU (ADD/MUL/MAD)，寄存器输出
- **延迟隐藏**：Warp 在 LD 等待期间，scheduler 调度其他 Warp

### 双调度器架构

```
每个 SM:
  - 2 个 WarpScheduler
  - Scheduler 0: 管理 Warp 0, 1
  - Scheduler 1: 管理 Warp 2, 3
  - 每周期可并行执行 2 个 Warp（如果资源允许）
  - 资源仲裁：GlobalMem 和 SharedMem 优先级仲裁
```

**性能提升**：
- 纯计算程序：2.00× 加速
- 内存密集程序：3.00× 加速（延迟隐藏）

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
// Warp 在访存时主动进入等待状态
is(GpuOpcode.LD) {
  state := sMemWait  // 主动让出时间片
  io.busy := true    // 通知调度器"我在等待"
}

// 调度器跳过 busy 的 Warp
schedulers(s).io.warpHalted(w) := warps(warpId).io.halted || warps(warpId).io.busy
```

**关键特点**：
- ✅ Warp **主动**让出时间片（协作式）
- ✅ 调度器**不会强制抢占**
- ✅ 在访存时切换，实现延迟隐藏

详见 [warp_scheduling.md](warp_scheduling.md#协作式调度)

---

## 3. 存储层次

| 层级 | 类型 | 深度 | 延迟 | 共享范围 |
|------|------|------|------|----------|
| GlobalMem | Mem | 4096 | Warp 内计数器 (默认10) | 全局 (4 SM 共享) |
| SharedMem (per-SM) | SyncReadMem | 256 | 1 cycle | SM 内 |
| RegFile (per-Warp) | Reg | 16×4 | 0 | Warp 私有 |

---

## 4. 指令集 (8 条)

详见 [isa.md](../isa.md#gpu-指令集)

格式：`[31:28]op [27:24]rd [23:20]rs1 [19:16]rs2 [15:12]rs3 [11:0]imm12`

| 操作码 | 助记符 | 功能 |
|--------|--------|------|
| 0x0 | NOP | 空操作 |
| 0x1 | HALT | 停机 (当前 Warp) |
| 0x2 | LD | Rd = GlobalMem[Rs1 + imm] |
| 0x3 | ST | GlobalMem[Rs1 + imm] = Rs2 |
| 0x4 | ADD | Rd = Rs1 + Rs2 |
| 0x5 | MUL | Rd = Rs1 × Rs2 |
| 0x6 | MAD | Rd = Rs1 × Rs2 + Rs3 |
| 0x7 | SHM | SharedMem 操作 |

---

## 5. 性能数据

### 向量加法 (latency=1)

```
程序：LD×2 → ADD → ST → HALT
4 SM 并行

性能：
  总周期：10
  活跃 Warp 周期：10
  Warp 利用率：25.0%
```

### 向量加法 (latency=10)

```
程序：LD×2 → ADD → ST → HALT
4 SM 并行

性能：
  总周期：28
  活跃 Warp 周期：20
  Warp 利用率：14.7%
  延迟隐藏效果：显著
```

### 双调度器性能提升

| 测试场景 | 单调度器 | 双调度器 | 加速比 |
|---------|---------|---------|--------|
| **纯计算（10条ADD）** | 44 周期 | 22 周期 | **2.00×** |
| **内存密集（latency=10）** | 84 周期 | 28 周期 | **3.00×** |
| **混合程序（latency=5）** | 60 周期 | 20 周期 | **3.00×** |

---

## 6. 与真实 GPU 的对比

详见 [warp_scheduling.md](warp_scheduling.md#与真实-gpu-对比)

| 维度 | 玩具 GPU | 真实 GPU (Ampere) | 差距 |
|------|---------|------------------|------|
| **Scheduler 数量** | 2 个 | 4 个 | 2× |
| **每周期指令** | 2 条 | 4 条 | 2× |
| **每 SM Warp 数** | 4 个 | 64 个 | 16× |
| **执行单元** | 共享 CudaCore | 分区 + 多类型单元 | - |
| **调度策略** | Round-Robin | Round-Robin + 优先级 | - |

**总体差距**：约 32× 并行度

---

## 7. 测试

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

## 8. 源代码

```
src/main/scala/gpu/
├── GpuParams.scala         # GPU 参数配置
├── CudaCore.scala          # CUDA Core (ADD/MUL/MAD)
├── Warp.scala              # Warp (4 线程 SIMT + 延迟模型)
├── WarpScheduler.scala     # Round-Robin 调度器
├── SM.scala                # Streaming Multiprocessor (双调度器)
└── ToyGpuTop.scala         # 顶层 (4×SM + GlobalMem)
```

---

## 相关文档

- [Warp 调度详解](warp_scheduling.md) - 调度算法、协作式调度、延迟隐藏
- [双调度器总结](dual_scheduler_summary.md) - 双调度器实现和性能提升
- [性能对比](performance_comparison.md) - NPU vs GPU 性能分析
- [指令集](../isa.md) - 详细指令说明
- [主文档](../README.md) - 返回文档索引
