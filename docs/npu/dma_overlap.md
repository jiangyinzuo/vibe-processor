# DMA-Compute Overlap 优化

## 概述

本文档描述 NPU 中实现的 DMA-Compute Overlap 优化，允许数据传输（DMA）和计算（MATMUL）同时进行，从而提高硬件利用率。

## 架构改进

### 1. 非阻塞 DMA 指令

**旧设计：**
- `DMA_LOAD` 和 `DMA_STORE` 是阻塞指令
- Scalar Unit 等待 DMA 完成后才继续执行
- DMA 和计算无法重叠

**新设计：**
- `DMA_LOAD` (0x8) 和 `DMA_STORE` (0x9) 变为非阻塞指令
- 指令立即返回，DMA 在后台执行
- 新增 `DMA_WAIT` (0xA) 指令显式等待所有 DMA 完成

### 2. DMA 请求队列

**实现细节：**
```scala
// AiCore.scala
val dmaQueueDepth = 4
val dmaQueue = RegInit(VecInit.fill(dmaQueueDepth)(0.U.asTypeOf(new Bundle {
  val isStore = Bool()
  val hbmAddr = UInt(AscendParams.HBMAddrW.W)
  val ubAddr  = UInt(AscendParams.UBAddrW.W)
})))
```

**特性：**
- 深度为 4 的 FIFO 队列
- 支持多个 DMA 请求并发飞行
- Head/Tail 指针管理入队/出队
- `dmaQueueFull` 和 `dmaQueueEmpty` 信号

### 3. UB 双端口分离

**旧设计：**
- UB 只有一个端口
- Scalar LOAD/STORE 和 DMA 通过 Mux 仲裁
- 无法同时访问

**新设计：**
```scala
// ToyAscendTop.scala
// Port A: Scalar LOAD/STORE (UB ↔ L0)
ub.io.portA.en    := core.io.ubEn
ub.io.portA.we    := core.io.ubWe
ub.io.portA.addr  := core.io.ubAddr

// Port B: DMA (L2 ↔ UB)
ub.io.portB.en    := core.io.ubEnB
ub.io.portB.we    := core.io.ubWeB
ub.io.portB.addr  := core.io.ubAddrB
```

**优势：**
- Scalar 和 DMA 可以同时访问 UB
- 消除了端口仲裁的气泡周期
- 为 overlap 提供硬件基础

### 4. L0 双缓冲架构

**设计：**
```scala
// ScalarUnit.scala
// L0A: 激活输入缓存（双缓冲）
val l0aBuf0 = RegInit(VecInit.fill(n, n)(0.S(dw.W)))
val l0aBuf1 = RegInit(VecInit.fill(n, n)(0.S(dw.W)))
val l0aActiveSel = RegInit(false.B)  // false -> buf0 活跃, true -> buf1 活跃

// L0B: 权重输入缓存（双缓冲）
val l0bBuf0 = RegInit(VecInit.fill(n, n)(0.S(dw.W)))
val l0bBuf1 = RegInit(VecInit.fill(n, n)(0.S(dw.W)))
val l0bActiveSel = RegInit(false.B)
```

**工作原理：**
- 每个 L0 buffer (L0A/L0B) 有两个物理缓冲区
- Active buffer：当前供 CubeUnit 读取
- Fill buffer：后台由 LOAD 指令写入下一批数据
- LOAD 完成后自动切换 active/fill buffer

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

### 模式 3：双缓冲预取（未来扩展）

```scala
// 使用 L0 双缓冲实现计算 + 预取重叠
// 当前实现：LOAD 完成后立即切换 buffer
// 未来优化：在 MATMUL 期间预取下一个 tile 到 fill buffer
```

## 性能计数器

### 新增字段

```scala
class PerfCounters extends Bundle {
  // ... 现有字段 ...
  val overlapCycles = UInt(32.W)  // DMA 和 Compute 重叠的周期数
}
```

### 计算逻辑

```scala
// AiCore.scala
val cubeActive = RegInit(false.B)
when(scalar.io.cubeStart) { cubeActive := true.B }
when(cube.io.done)        { cubeActive := false.B }

val dmaRunning = dmaState =/= sDmaIdle && dmaState =/= sDmaDone

// Overlap cycles: compute and DMA running simultaneously
when(cubeActive && dmaRunning) {
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
总周期数:            187
Cube 计算周期:        15
DMA 周期:             72
重叠周期:              0
气泡周期:              3
计算效率:           8.0%
DMA 占比:           38.5%
重叠率:             0.0%
理论峰值利用率:     83.3%
```

**分析：**
- 该测试使用顺序执行模式（DMA_WAIT 在 MATMUL 之前）
- 重叠周期为 0 是预期行为
- DMA 占比 38.5% 说明数据传输是主要瓶颈

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
   - Scalar LOAD/STORE 和 DMA 必须访问不同的 UB 地址
   - 需要程序员手动管理地址分配

2. **Buffer 切换时机：**
   - LOAD 完成后立即切换 active buffer
   - 无法在 MATMUL 期间预取到 fill buffer

3. **队列深度：**
   - DMA 队列深度为 4
   - 超过 4 个并发请求会阻塞

### 未来优化

1. **智能 Buffer 切换：**
   - 在 MATMUL 开始时切换 buffer
   - 允许在计算期间预取下一个 tile

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
