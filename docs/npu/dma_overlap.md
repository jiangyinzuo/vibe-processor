# MTE-Compute Overlap 优化

## 概述

本文档描述 NPU 中实现的 MTE-Compute Overlap 优化。当前 AiCore 已拆成 Scalar dispatcher、AIC、AIV、MTE1、MTE2、MTE3；数据搬运和计算可以在不同通路上重叠执行。

## 架构改进

### 1. 非阻塞传输指令

**旧设计：**
- `DMA_LOAD` 和 `DMA_STORE` 是阻塞指令
- Scalar Unit 等待 DMA 完成后才继续执行
- DMA 和计算无法重叠

**新设计：**
- `DMA_LOAD` (0x8) 和 `DMA_STORE` (0x9) 入队到 MTE2，由 MTE2 后台执行 L2↔UB
- `LOAD` (0x2) 在 MTE1 空闲时启动 UB→L1 staging→L0A/L0B，然后继续取指
- 新增 `DMA_WAIT` (0xA) 指令显式等待所有 DMA 完成

### 2. MTE2 DMA 请求队列

**实现细节：**
```scala
// AiCore.scala
val dmaQueueDepth = 4
val dmaQueue = RegInit(VecInit.fill(dmaQueueDepth)(0.U.asTypeOf(new Bundle {
  val isStore = Bool()
  val l2Addr  = UInt(AscendParams.L2AddrW.W)
  val ubAddr  = UInt(AscendParams.UBAddrW.W)
})))
```

**特性：**
- 深度为 4 的 FIFO 队列
- 支持多个 DMA 请求挂起，MTE2 按队列顺序执行
- Head/Tail 指针管理入队/出队
- `dmaQueueFull` 和 `dmaQueueEmpty` 信号

### 3. UB 双端口分离

**旧设计：**
- UB 只有一个端口
- Scalar LOAD/STORE 和 DMA 通过 Mux 仲裁
- 无法同时访问

**新设计：**
```scala
// AiCore.scala
// Port A: MTE1 / MTE3 / AIV 共享本地存储通路
ub.io.portA.en    := core.io.ubEn
ub.io.portA.we    := core.io.ubWe
ub.io.portA.addr  := core.io.ubAddr

// Port B: MTE2 独占 L2 ↔ UB 通路
ub.io.portB.en    := core.io.ubEnB
ub.io.portB.we    := core.io.ubWeB
ub.io.portB.addr  := core.io.ubAddrB
```

**优势：**
- 本地 MTE/AIV 和 MTE2 可以同时访问 UB
- L2↔UB 传输不阻塞 AIC 侧本地 LOAD/MATMUL
- 为 overlap 提供硬件基础

### 4. AIC L0 tile FIFO

**设计：**
```scala
// AicCore.scala
val l0a = RegInit(VecInit.fill(tileSlots, n, n)(0.S(dw.W)))
val l0b = RegInit(VecInit.fill(tileSlots, n, n)(0.S(dw.W)))
val l0c = RegInit(VecInit.fill(n, n)(0.S(aw.W)))

val fillSlot    = RegInit(0.U(slotW.W))
val computeSlot = RegInit(0.U(slotW.W))
val l0aReady    = RegInit(VecInit.fill(tileSlots)(false.B))
val l0bReady    = RegInit(VecInit.fill(tileSlots)(false.B))
```

**工作原理：**
- MTE1 把 UB 行读入 L1 staging，再写入 AIC 的 L0A/L0B
- L0A/L0B 每侧有 4 个 tile slot，完整 ACT+WEIGHT ready 后进入可计算队列
- AIC 从最老的 ready slot 启动 Cube，计算结果进入 L0C
- MTE3 从 L0C 读回 UB，供后续 MTE2 写回 L2

## 指令集更新

### DMA_LOAD (0x8)
```
格式: [31:28]=0x8 | [27:20]=ub_base | [19:4]=l2_base
功能: 从 L2[l2_base..l2_base+N-1] 加载 N 行到 UB[ub_base..ub_base+N-1]
行为: 非阻塞，立即返回
```

### DMA_STORE (0x9)
```
格式: [31:28]=0x9 | [27:20]=ub_base | [19:4]=l2_base
功能: 从 UB[ub_base..ub_base+N-1] 存储 N 行到 L2[l2_base..l2_base+N-1]
行为: 非阻塞，立即返回
```

### DMA_WAIT (0xA)
```
格式: [31:28]=0xA
功能: 等待所有飞行中的 DMA 请求完成
行为: 阻塞，直到 dmaQueueEmpty 为 true
```

## 编程模式

### 模式 1：顺序执行（无 Overlap）

```scala
// 传统模式：DMA 和计算串行
DMA_LOAD(ub=0, l2=0)      // 加载数据
DMA_LOAD(ub=N, l2=N)
DMA_WAIT                   // 等待 DMA 完成
LOAD(bufSel=1, mem=0)      // UB -> L0
LOAD(bufSel=0, mem=N)
MATMUL                     // 计算
STORE(bufSel=2, mem=2*N)   // L0 -> UB
DMA_STORE(ub=2*N, l2=2*N)  // 写回结果
DMA_WAIT
```

**性能特征：**
- DMA 周期和计算周期完全串行
- 重叠周期 = 0
- 简单但效率低

### 模式 2：流水线 Overlap

```scala
// Tile 0: 初始加载
DMA_LOAD(ub=0, l2=0)       // 加载 tile 0
DMA_LOAD(ub=N, l2=N)
DMA_WAIT
LOAD(bufSel=1, mem=0)
LOAD(bufSel=0, mem=N)

// Tile 1: 预取 + 计算重叠
DMA_LOAD(ub=0, l2=2*N)     // 预取 tile 1（非阻塞）
DMA_LOAD(ub=N, l2=3*N)     // 预取 tile 1（非阻塞）
MATMUL                     // 计算 tile 0（与 DMA 重叠！）
STORE(bufSel=2, mem=4*N)
DMA_WAIT                   // 等待 tile 1 数据
LOAD(bufSel=1, mem=0)      // 加载 tile 1 到 L0
LOAD(bufSel=0, mem=N)

// Tile 2: 继续重叠
DMA_LOAD(ub=0, l2=4*N)     // 预取 tile 2
DMA_LOAD(ub=N, l2=5*N)
MATMUL                     // 计算 tile 1（与 DMA 重叠！）
STORE(bufSel=2, mem=5*N)
DMA_WAIT
...
```

**性能特征：**
- DMA 和 MATMUL 并行执行
- 重叠周期 > 0
- 接近理论峰值性能

### 模式 3：本地 LOAD/MATMUL 重叠

```scala
// 当前实现：AIC L0A/L0B tile FIFO 支持本地预取
LOAD(bufSel=1, mem=nextAct)  // MTE1 后台写入下一个 slot
LOAD(bufSel=0, mem=nextWei)
MATMUL                       // AIC 消费已有 ready slot
```

## 性能计数器

### 新增字段

```scala
class PerfCounters extends Bundle {
  // ... 现有字段 ...
  val overlapCycles = UInt(32.W)  // AIC 和 MTE1/MTE2 重叠的周期数
}
```

### 计算逻辑

```scala
// AiCore.scala
val aicActive = RegInit(false.B)
when(scalar.io.aicStart) { aicActive := true.B }
when(aic.io.done)        { aicActive := false.B }

when(mte2.io.busy) { perf.dmaTotalCycles := perf.dmaTotalCycles + 1.U }
when(aicActive && (mte1.io.busy || mte2.io.busy)) {
  perf.overlapCycles := perf.overlapCycles + 1.U
}
```

### 性能指标

**重叠率 (Overlap Ratio):**
```
重叠率 = overlapCycles / dmaTotalCycles × 100%
```

- 0%：无重叠（顺序执行）
- 100%：完全重叠（理想情况）
- 实际值取决于程序结构和 tile 大小

## 测试结果

### IntegrationTest（8×8 矩阵乘法）

```
=== NPU 性能统计 (8×8 矩阵乘法) ===
总周期数:            181
DMA 周期:             72
重叠周期:              22
```

**分析：**
- 基础集成测试已经能观察到 AIC 与 MTE 的局部重叠
- 更深的 tile 流水线由 Pipeline3Test 和 TripleBufferTest 覆盖

### 理论性能提升

**假设：**
- MATMUL 周期：15
- DMA 周期（每个 tile）：36
- Tile 数量：N

**顺序执行：**
```
总周期 = N × (DMA + MATMUL) = N × (36 + 15) = 51N
```

**流水线 Overlap：**
```
总周期 = DMA_initial + N × max(DMA, MATMUL) = 36 + N × 36 = 36(N+1)
加速比 = 51N / 36(N+1) ≈ 1.42× (当 N 较大时)
```

## 限制与未来工作

### 当前限制

1. **UB 地址冲突：**
   - MTE1/MTE3/AIV 与 MTE2 最好访问不同的 UB 地址区间
   - 需要程序员手动管理地址分配

2. **MTE2 执行宽度：**
   - DMA 队列可挂起多个请求
   - 当前 MTE2 一次只执行一个 L2↔UB 请求

3. **队列深度：**
   - DMA 队列深度为 4
   - 超过 4 个并发请求会阻塞

### 未来优化

1. **更真实的 bank/冲突模型：**
   - 为 UB/L0/L1 staging 增加 bank 和端口冲突
   - 用 bank conflict 影响实际吞吐

2. **自动地址管理：**
   - 硬件自动检测地址冲突
   - 动态分配 UB 空间

3. **更大的队列：**
   - 增加 DMA 队列深度到 8 或 16
   - 支持更深的流水线

4. **DMA 优先级：**
   - 为不同类型的 DMA 请求分配优先级
   - 优先处理关键路径上的数据传输

## 参考

- [NPU 架构文档](architecture.md)
- [性能分析](../performance_comparison.md)
- [ISA 文档](../isa.md)
