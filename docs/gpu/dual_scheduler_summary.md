# 双调度器实现总结

## ✅ 实现完成

成功为玩具 GPU 的每个 SM 实现了 **2 个 Warp Scheduler**，实现真正的多 Warp 并行执行。

---

## 架构改进

### 之前（单调度器）
```
每个 SM:
  - 1 个 WarpScheduler
  - 管理 4 个 Warp
  - 每周期只能执行 1 个 Warp
  - 串行执行
```

### 现在（双调度器）
```
每个 SM:
  - 2 个 WarpScheduler
  - Scheduler 0: 管理 Warp 0, 1
  - Scheduler 1: 管理 Warp 2, 3
  - 每周期可以并行执行 2 个 Warp
  - 并行执行（如果资源允许）
```

---

## 性能计数（当前实测数据）

### 1. 纯计算程序（无内存访问）

**测试程序**：10 条 ADD 指令

| 指标 | 数值 |
|------|------|
| **总周期数** | 85 |
| **Live Warp 周期** | 252 |
| **Eligible Warp 周期** | 252 |
| **Stalled Warp 周期** | 0 |
| **No-eligible 周期** | 0 |
| **ALU issue 周期** | 20 |

**分析**：
- 纯计算没有访存等待，因此 `stalledWarpCycles=0` 且 `noEligibleCycles=0`
- 周期数主要来自当前 dispatcher/RF/WB 流水线和 CTA 调度开销
- `ALU issue=20` 说明 4 个 warp 的 ADD work 已实际进入 ALU lane

### 2. 内存密集程序（latency=10）

**测试程序**：LD → LD → ADD → ST

| 指标 | 数值 |
|------|------|
| **总周期数** | 50 |
| **Live Warp 周期** | 190 |
| **Eligible Warp 周期** | 102 |
| **Stalled Warp 周期** | 88 |
| **No-eligible 周期** | 14 |
| **MEM issue 周期** | 12 |

**分析**：
- 4 个 warp 共发出 8 次 LD 和 4 次 ST，因此 `MEM issue=12`
- `stalledWarpCycles=88` 表示访存压力存在
- `noEligibleCycles=14` 表示只有 14 个周期所有 live warp 都在等；其它周期仍有 Ready warp 可调度

### 3. 混合程序（计算 + 内存，latency=5）

**测试程序**：LD → ADD×5 → ST

| 指标 | 数值 |
|------|------|
| **总周期数** | 62 |
| **Live Warp 周期** | 238 |
| **Eligible Warp 周期** | 214 |
| **Stalled Warp 周期** | 24 |
| **No-eligible 周期** | 2 |
| **ALU issue 周期** | 11 |
| **MEM issue 周期** | 8 |

**分析**：
- 该程序计算更多，`eligibleWarpCycles=214`，绝大多数周期至少有 Ready warp
- `noEligibleCycles=2` 表示 latency=5 基本被其它 warp 和计算覆盖

---

## 关键技术实现

### 1. Warp 分配策略

```scala
// 将 4 个 Warp 平均分配给 2 个 Scheduler
val numSchedulers = 2
val warpsPerScheduler = numWarps / numSchedulers  // 2

// Scheduler 0: Warp 0, 1
// Scheduler 1: Warp 2, 3
for (s <- 0 until numSchedulers) {
  for (w <- 0 until warpsPerScheduler) {
    val warpId = s * warpsPerScheduler + w
    schedulers(s).io.warpHalted(w) := warps(warpId).io.halted || warps(warpId).io.busy
  }
}
```

### 2. 资源仲裁机制

**GlobalMem 仲裁**：
```scala
// 如果两个 Warp 同时请求 GlobalMem，使用优先级仲裁
val gmemRequests = VecInit(warps.zipWithIndex.map { case (w, i) => 
  w.io.gmemEn && combinedGrant(i) 
})
val gmemGrantIdx = PriorityEncoder(gmemRequests.asUInt)  // 优先级：Warp 0 > 1 > 2 > 3

// 只有优先级最高的 Warp 获得 GlobalMem 访问权
for (i <- 0 until numWarps) {
  when(hasGmemRequest && gmemGrantIdx === i.U) {
    io.gmemEn    := warps(i).io.gmemEn
    io.gmemWe    := warps(i).io.gmemWe
    io.gmemAddr  := warps(i).io.gmemAddr
    io.gmemWdata := warps(i).io.gmemWdata
  }
}
```

**SharedMem 仲裁**：
- 同样使用优先级仲裁
- 避免多个 Warp 同时写入导致冲突

### 3. Grant 信号合并

```scala
// 合并两个 Scheduler 的 grant 信号
val combinedGrant = Wire(Vec(numWarps, Bool()))
for (s <- 0 until numSchedulers) {
  for (w <- 0 until warpsPerScheduler) {
    val warpId = s * warpsPerScheduler + w
    combinedGrant(warpId) := schedulers(s).io.grant(w)
  }
}
```

---

## 性能分析

### 如何从计数器判断延迟隐藏？

内存密集程序（`latency=10`）的核心数据：

```
totalCycles         = 50
eligibleWarpCycles  = 102
stalledWarpCycles   = 88
noEligibleCycles    = 14
memIssueCycles      = 12
```

解释：

- `stalledWarpCycles=88`：很多 warp-cycle 花在等待 GlobalMem。
- `eligibleWarpCycles=102`：等待发生的同时，SM 里仍有不少 Ready warp。
- `noEligibleCycles=14`：只有 14 个周期所有 live warp 都在等内存，这些周期才是无法隐藏的访存气泡。
- `memIssueCycles=12`：4 个 warp 的 `2×LD + 1×ST` 全部进入 memory path。

因此，当前实现展示的是“部分隐藏”：访存压力存在，但并没有把每个 warp 的等待完全串行暴露到总周期中。

### Warp 利用率分析

需要区分三个指标：

```
Warp 占用率 = liveWarpCycles / (totalCycles × residentWarps)
Ready 覆盖率 = eligibleWarpCycles / (totalCycles × residentWarps)
No-eligible 周期 = 有 live warp 但没有 Ready warp 的周期
```

`liveWarpCycles` 说明 occupancy，`eligibleWarpCycles` 说明调度器是否还有可发射的 warp，`stalledWarpCycles` 说明访存压力，`noEligibleCycles` 说明访存压力真正暴露成前端空泡。

**如何提高利用率？**
1. ✅ 增加 Warp 数量（如 8 个或 16 个）
2. ✅ 减少内存延迟（使用 SharedMem）
3. ✅ 增加计算密度（更多 ADD/MUL 指令）

---

## 与真实 GPU 的对比

| 维度 | 本项目（双调度器） | 真实 GPU (Ampere) | 差距 |
|------|------------------|------------------|------|
| **Scheduler 数量** | 2 个 | 4 个 | 2× |
| **每周期指令** | 2 条 | 4 条 | 2× |
| **每 SM Warp 数** | 4 个 | 64 个 | 16× |
| **资源仲裁** | 简单优先级 | 复杂仲裁 + Scoreboarding | - |
| **执行单元** | 共享 CudaCore | 分区 + 多类型单元 | - |

**总体差距**：
- 并行度：2× (调度器) × 16× (Warp 数) = **32×**
- 但核心概念已实现：多调度器、资源仲裁、延迟隐藏

---

## 进一步优化方向

### 1. 增加到 4 个 Scheduler
```scala
val numSchedulers = 4
val warpsPerScheduler = 1  // 每个 Scheduler 管理 1 个 Warp

// 每周期可以并行执行 4 个 Warp
// 性能提升：4×（理想情况）
```

### 2. 增加 Warp 数量
```scala
val numWarps = 8  // 从 4 增加到 8

// 更好的延迟隐藏
// Warp 利用率提升
```

### 3. 资源分区
```scala
// 为每个 Scheduler 分配独立的执行单元
val corePartitions = Array.fill(numSchedulers)(
  Array.fill(coresPerPartition)(Module(new CudaCore))
)

// 避免资源冲突
// 真正的并行执行
```

### 4. 双指令发射
```scala
// 单个 Warp 每周期发射 2 条指令
// 条件：无依赖关系 + 不同执行单元

// 性能提升：2×（单 Warp 吞吐量）
```

---

## 测试验证

### 运行测试
```bash
# 双调度器性能测试
sbt "testOnly gpu.DualSchedulerTest"

# GPU 集成测试（验证兼容性）
sbt "testOnly gpu.GpuIntegrationTest"
```

### 测试结果
- ✅ 所有测试通过
- ✅ 纯计算程序：`stalledWarpCycles=0`，验证计数器不会误报访存等待
- ✅ 内存密集程序：`stalledWarpCycles=88`、`noEligibleCycles=14`，验证延迟被部分隐藏
- ✅ 混合程序：`noEligibleCycles=2`，多数访存等待被计算和其它 warp 覆盖
- ✅ 向后兼容：原有测试全部通过

---

## 总结

### 成功实现
✅ **2 个 Warp Scheduler** 并行执行
✅ **资源仲裁机制**（GlobalMem + SharedMem）
✅ **延迟隐藏计数器**（eligible/stalled/no-eligible）
✅ **Issue 计数器**（ALU/SFU/MEM/ALU+SFU）

### 核心价值
- 展示了真实 GPU 的多调度器架构
- 理解了并行执行和资源仲裁的权衡
- 验证了延迟隐藏的有效性

### 与真实 GPU 的差距
- 调度器数量：2 vs 4
- Warp 数量：4 vs 64
- 执行单元：共享 vs 分区
- 总体差距：~32×

### 教学意义
- ✅ 保留了核心概念（多调度器、仲裁、延迟隐藏）
- ✅ 可以通过计数器区分 occupancy、访存等待和真正暴露的空泡
- ✅ 性能现象可量化，不依赖肉眼看波形
- ✅ 为理解真实 GPU 打下基础

---

## 相关文档

- `docs/warp_scheduling.md` - Warp 调度与执行详解
- `docs/multi_warp_execution.md` - 真实 GPU 的多 Warp 并行执行
- `src/main/scala/gpu/SM.scala` - 共享架构 SM 与双调度器实现
- `src/test/scala/gpu/DualSchedulerTest.scala` - 性能测试
