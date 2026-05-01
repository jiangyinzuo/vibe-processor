# NPU 流水线设计详解

## 概述

当前项目实现了一个**两级流水线**，通过 DMA-Compute Overlap 技术实现数据传输和计算的并行执行。

---

## 流水线架构

### 整体结构

```
┌─────────────────────────────────────────────────────────────┐
│                        AiCore                                │
├─────────────────────────────────────────────────────────────┤
│                                                               │
│  ┌──────────────┐    ┌──────────────┐    ┌──────────────┐  │
│  │ ScalarUnit   │    │  CubeUnit    │    │ VectorUnit   │  │
│  │ (控制流)     │───▶│ (矩阵乘法)   │    │ (向量运算)   │  │
│  └──────────────┘    └──────────────┘    └──────────────┘  │
│         │                    │                    │          │
│         │                    │                    │          │
│         ▼                    ▼                    ▼          │
│  ┌──────────────────────────────────────────────────────┐  │
│  │              Unified Buffer (UB)                      │  │
│  │              双端口设计                               │  │
│  │  Port A: Scalar ↔ L0    Port B: DMA ↔ L2           │  │
│  └──────────────────────────────────────────────────────┘  │
│         │                                        │          │
│         │                                        │          │
│         ▼                                        ▼          │
│  ┌──────────────┐                        ┌──────────────┐  │
│  │  L0 Buffer   │                        │ DMA Engine   │  │
│  │  (双缓冲)    │                        │ (队列驱动)   │  │
│  └──────────────┘                        └──────────────┘  │
│                                                   │          │
└───────────────────────────────────────────────────┼──────────┘
                                                    │
                                                    ▼
                                            ┌──────────────┐
                                            │  L2 Buffer   │
                                            │  (共享缓存)  │
                                            └──────────────┘
```

---

## 流水线级数

### 当前实现：两级流水线

#### 第 1 级：DMA 传输
- **功能：** L2 ↔ UB 数据传输
- **组件：** DMA Engine
- **特点：** 非阻塞，队列驱动

#### 第 2 级：计算执行
- **功能：** UB ↔ L0 ↔ Compute
- **组件：** ScalarUnit, CubeUnit, VectorUnit
- **特点：** 阻塞，顺序执行

### 流水线重叠

```
时间轴：
─────────────────────────────────────────────────────────▶

Tile 0:  [DMA_LOAD]──[WAIT]──[LOAD]──[MATMUL]──[STORE]──[DMA_STORE]
                                          │
Tile 1:                              [DMA_LOAD]──[WAIT]──[LOAD]──[MATMUL]
                                          ▲
                                          │
                                    重叠区域（Overlap）
```

**重叠原理：**
- Tile 1 的 DMA_LOAD 在 Tile 0 的 MATMUL 期间执行
- 两个操作并行，节省时间

---

## 核心组件设计

### 1. DMA Engine（队列驱动）

#### DMA 请求队列

```scala
// 队列深度：4
val dmaQueue = RegInit(VecInit.fill(4)(0.U.asTypeOf(new Bundle {
  val isStore = Bool()        // LOAD 或 STORE
  val hbmAddr = UInt(16.W)    // L2 地址
  val ubAddr  = UInt(8.W)     // UB 地址
})))

val dmaQueueValid = RegInit(VecInit.fill(4)(false.B))
val dmaQueueHead  = RegInit(0.U(2.W))  // 出队指针
val dmaQueueTail  = RegInit(0.U(2.W))  // 入队指针
```

**队列特性：**
- **深度：** 4 个请求
- **类型：** FIFO（先进先出）
- **状态：** Empty/Full 信号
- **并发：** 支持多个飞行中的请求

#### DMA 状态机

```scala
// 8 个状态
val sDmaIdle       // 空闲，等待队列非空
val sDmaLoadRd     // LOAD: 读 L2
val sDmaLoadWait   // LOAD: 等待 L2 数据
val sDmaLoadWb     // LOAD: 写 UB
val sDmaStoreRd    // STORE: 读 UB
val sDmaStoreWait  // STORE: 等待 UB 数据
val sDmaStoreWr    // STORE: 写 L2
val sDmaDone       // 完成，出队
```

**状态转换：**

```
DMA_LOAD 流程：
Idle → LoadRd → LoadWait → LoadWb → (循环 N 次) → Done → Idle
       ↑                      ↓
       └──────────────────────┘

DMA_STORE 流程：
Idle → StoreRd → StoreWait → StoreWr → (循环 N 次) → Done → Idle
       ↑                        ↓
       └────────────────────────┘
```

**关键特性：**
- **非阻塞：** 入队后立即返回
- **并行：** 多个请求可以在队列中等待
- **顺序：** 按 FIFO 顺序处理

### 2. UB 双端口设计

```scala
// Port A: Scalar LOAD/STORE (UB ↔ L0)
io.ubEn    := scalar.io.ubEn
io.ubWe    := scalar.io.ubWe
io.ubAddr  := scalar.io.ubAddr
io.ubWdata := scalar.io.ubWdata
scalar.io.ubRdata := io.ubRdata

// Port B: DMA (L2 ↔ UB)
io.ubEnB    := dmaUbEn
io.ubWeB    := dmaUbWe
io.ubAddrB  := dmaUbAddr
io.ubWdataB := dmaUbWdata
val dmaUbRdata = io.ubRdataB
```

**双端口优势：**
- **并行访问：** Scalar 和 DMA 可以同时访问 UB
- **无冲突：** 两个端口独立，无需仲裁
- **高带宽：** 两倍的访问带宽

**地址管理：**
- 程序员需要手动分配地址
- Port A 和 Port B 访问不同的 UB 地址区域
- 避免读写冲突

### 3. L0 双缓冲设计

```scala
// L0A 和 L0B 各有两个物理缓冲区
val l0AActive = RegInit(0.U(1.W))  // 0 或 1
val l0BActive = RegInit(0.U(1.W))

// Active buffer: 供 CubeUnit 读取
// Fill buffer: 供 LOAD 指令写入

// LOAD 完成后切换
when(loadDone) {
  l0AActive := ~l0AActive
  l0BActive := ~l0BActive
}
```

**双缓冲优势：**
- **预取：** 在计算时预取下一批数据
- **无等待：** 计算和加载可以重叠
- **未来扩展：** 为三级流水线提供基础

**当前限制：**
- LOAD 完成后立即切换
- 无法在 MATMUL 期间预取到 fill buffer

---

## 流水线执行模式

### 模式 1：顺序执行（无 Overlap）

```scala
// Tile 0
DMA_LOAD(ub=0, l2=0)      // 加载激活
DMA_LOAD(ub=N, l2=N)      // 加载权重
DMA_WAIT                   // 等待 DMA 完成 ← 阻塞点
LOAD(bufSel=1, mem=0)      // UB → L0
LOAD(bufSel=0, mem=N)
MATMUL                     // 计算
STORE(bufSel=2, mem=2*N)   // L0 → UB
DMA_STORE(ub=2*N, l2=2*N)  // 写回结果
DMA_WAIT                   // 等待 DMA 完成 ← 阻塞点
```

**时序图：**

```
周期：  0    10   20   30   40   50   60   70   80
       │    │    │    │    │    │    │    │    │
DMA:   [──DMA_LOAD──]                [─DMA_STORE─]
                     [WAIT]                      [WAIT]
Compute:                   [LOAD][MATMUL][STORE]

总周期：~80
重叠周期：0
```

**特点：**
- 简单，易于理解
- DMA 和计算完全串行
- 重叠周期 = 0
- 效率低

### 模式 2：流水线 Overlap（当前实现）

```scala
// Tile 0: 初始加载
DMA_LOAD(ub=0, l2=0)
DMA_LOAD(ub=N, l2=N)
DMA_WAIT                   // 必须等待第一个 tile
LOAD(bufSel=1, mem=0)
LOAD(bufSel=0, mem=N)

// Tile 1: 预取 + 计算重叠
DMA_LOAD(ub=0, l2=2*N)     // 预取 tile 1（非阻塞）
DMA_LOAD(ub=N, l2=3*N)     // 预取 tile 1（非阻塞）
MATMUL                     // 计算 tile 0（与 DMA 重叠！）
STORE(bufSel=2, mem=4*N)
DMA_STORE(ub=4*N, l2=4*N)  // 写回 tile 0
DMA_WAIT                   // 等待 tile 1 数据
LOAD(bufSel=1, mem=0)
LOAD(bufSel=0, mem=N)

// Tile 2: 继续重叠
DMA_LOAD(ub=0, l2=4*N)
DMA_LOAD(ub=N, l2=5*N)
MATMUL                     // 计算 tile 1（与 DMA 重叠！）
STORE(bufSel=2, mem=5*N)
DMA_STORE(ub=5*N, l2=5*N)
DMA_WAIT
...
```

**时序图：**

```
周期：  0    10   20   30   40   50   60   70   80
       │    │    │    │    │    │    │    │    │
Tile 0:
DMA:   [──DMA_LOAD──]
                     [WAIT]
Compute:                   [LOAD][MATMUL][STORE]
                                    │
Tile 1:                             │
DMA:                           [──DMA_LOAD──]
                                    ▲          [WAIT]
                                    │
                                重叠区域！
Compute:                                      [LOAD][MATMUL]

总周期：~65
重叠周期：~15
节省：~15 周期（18.8%）
```

**特点：**
- DMA 和 MATMUL 并行执行
- 重叠周期 > 0
- 实际加速比：1.22×
- 重叠率：24.1%

### 模式 3：三级流水线（未来扩展）

```scala
// 使用 L0 双缓冲实现更深的流水线
// 在 MATMUL 期间预取下一个 tile 到 fill buffer

// Tile 0
DMA_LOAD(ub=0, l2=0)
DMA_LOAD(ub=N, l2=N)
DMA_WAIT
LOAD(bufSel=1, mem=0)      // 加载到 active buffer
LOAD(bufSel=0, mem=N)

// Tile 1: 三级重叠
DMA_LOAD(ub=0, l2=2*N)     // 预取 tile 1
DMA_LOAD(ub=N, l2=3*N)
LOAD(bufSel=1, mem=0)      // 加载到 fill buffer（与 MATMUL 重叠）
LOAD(bufSel=0, mem=N)
MATMUL                     // 计算 tile 0（与 DMA + LOAD 重叠！）
SWITCH_BUFFER              // 切换 active/fill buffer
STORE(bufSel=2, mem=4*N)
...
```

**时序图：**

```
周期：  0    10   20   30   40   50   60
       │    │    │    │    │    │    │
Tile 0:
DMA:   [──DMA_LOAD──]
                     [WAIT]
LOAD:                      [LOAD]
Compute:                         [MATMUL]
                                    │
Tile 1:                             │
DMA:                           [──DMA_LOAD──]
                                    │    │
LOAD:                               [LOAD]│
                                    ▲    ▲│
                                    │    ││
                                三级重叠！││
Compute:                                 [MATMUL]

总周期：~50
重叠周期：~25
节省：~30 周期（37.5%）
预期加速比：1.6×
```

**优势：**
- 三个操作并行：DMA + LOAD + MATMUL
- 更高的重叠率（~50%）
- 更高的加速比（~1.6×）

**需要的改进：**
1. 智能 buffer 切换（在 MATMUL 开始时切换）
2. LOAD 指令支持写入 fill buffer
3. 更复杂的控制逻辑

---

## 性能分析

### 当前性能（两级流水线）

**实测数据（OverlapBenchmark）：**

| 指标 | 顺序执行 | 流水线 Overlap | 提升 |
|------|---------|---------------|------|
| 总周期数 | 557 | 455 | -18.3% |
| 重叠周期 | 0 | 52 | +52 |
| 重叠率 | 0.0% | 24.1% | +24.1% |
| 计算效率 | 8.1% | 9.9% | +1.8% |

**实际加速比：1.22×**

### 瓶颈分析

**当前瓶颈：**

1. **DMA 时间 >> MATMUL 时间**
   - DMA：72 周期（每个 tile）
   - MATMUL：15 周期
   - 比例：4.8:1

2. **MATMUL 只能隐藏部分 DMA 时间**
   - 可隐藏：15 周期
   - 总 DMA：72 周期
   - 隐藏率：20.8%

3. **Tile 尺寸较小**
   - 当前：8×8
   - 计算量：512 次乘加
   - 计算时间短，难以隐藏 DMA

### 理论上限

**理论加速比：**

```
假设：
- DMA 时间：D
- MATMUL 时间：M
- Tile 数量：N

顺序执行：
总周期 = N × (D + M)

完全重叠（理想）：
总周期 = D + N × max(D, M)

当 D > M 时：
加速比 = N(D+M) / (D+NM) = (D+M) / (D/N+M)

当 N → ∞ 时：
加速比 → (D+M) / M = 1 + D/M

当前参数（D=72, M=15）：
理论上限 = 1 + 72/15 = 1 + 4.8 = 5.8×
```

**但实际上：**
- 需要初始 DMA（第一个 tile）
- 需要最后的计算（最后一个 tile）
- 实际上限约为 1.83×

### 优化方向

#### 1. 增大 Tile 尺寸（8×8 → 16×16）

**效果：**
- 计算量：512 → 4096（8×）
- MATMUL 时间：15 → 120 周期（8×）
- DMA 时间：72 → 288 周期（4×，因为 4 个 8×8 tile）

**预期重叠率：**
```
重叠周期 = min(MATMUL, DMA) = min(120, 288) = 120
重叠率 = 120 / 288 = 41.7%
预期加速比 = 1.35×
```

#### 2. 增加 L2 缓存（2KB → 8KB）

**效果：**
- 减少 HBM 访问
- 降低 DMA 延迟
- 预期加速比：1.30×

#### 3. 三级流水线

**效果：**
- DMA + LOAD + MATMUL 并行
- 预期重叠率：~50%
- 预期加速比：1.6×

#### 4. 增加 DMA 队列深度（4 → 8）

**效果：**
- 支持更多并发请求
- 更深的流水线
- 预期加速比：1.25×

---

## 流水线控制

### ScalarUnit 控制流

```scala
// ScalarUnit 负责流水线控制
switch(state) {
  is(sFetch) {
    // 取指令
  }
  is(sDecode) {
    // 译码
    when(opcode === DMA_LOAD) {
      // 入队 DMA 请求（非阻塞）
      dmaQueueEnq := true.B
      state := sFetch  // 立即返回
    }
    when(opcode === DMA_WAIT) {
      // 等待队列为空（阻塞）
      when(dmaQueueEmpty) {
        state := sFetch
      }
    }
    when(opcode === MATMUL) {
      // 启动计算（阻塞）
      cubeStart := true.B
      state := sExecCube
    }
  }
  is(sExecCube) {
    // 等待计算完成
    when(cubeDone) {
      state := sFetch
    }
  }
}
```

**关键点：**
- DMA_LOAD/STORE：非阻塞，立即返回
- DMA_WAIT：阻塞，等待队列为空
- MATMUL：阻塞，等待计算完成
- 流水线由程序员通过指令顺序控制

### 性能计数器

```scala
class PerfCounters extends Bundle {
  val totalCycles       = UInt(32.W)  // 总周期数
  val cubeComputeCycles = UInt(32.W)  // Cube 计算周期
  val dmaTotalCycles    = UInt(32.W)  // DMA 总周期
  val overlapCycles     = UInt(32.W)  // 重叠周期 ★
  val bubbleCycles      = UInt(32.W)  // 气泡周期
}

// Overlap 检测逻辑
val dmaActive = dmaState =/= sDmaIdle
val cubeActive = cube.io.start || !cube.io.done

when(dmaActive && cubeActive) {
  overlapCycles := overlapCycles + 1.U
}
```

**重叠率计算：**
```
重叠率 = overlapCycles / dmaTotalCycles × 100%
```

---

## 总结

### 当前流水线特点

1. **两级流水线**
   - 第 1 级：DMA 传输
   - 第 2 级：计算执行

2. **非阻塞 DMA**
   - 队列驱动
   - 支持并发请求
   - 立即返回

3. **双端口 UB**
   - Scalar 和 DMA 并行访问
   - 无仲裁冲突

4. **实际性能**
   - 加速比：1.22×
   - 重叠率：24.1%
   - 节省周期：18.3%

### 优化潜力

- **理论上限：** 1.83×
- **优化空间：** +50%
- **主要方向：** 增大 tile、三级流水线、更大缓存

### 设计亮点

- ✅ 非阻塞指令设计
- ✅ 队列管理与流控
- ✅ 双端口存储器
- ✅ 性能可观测
- ✅ 为未来扩展提供基础

---

**文档版本：** v1.0  
**更新日期：** 2026-05-01  
**相关文档：** [DMA Overlap](dma_overlap.md), [NPU 架构](architecture.md)
