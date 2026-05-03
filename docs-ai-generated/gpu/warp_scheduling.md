# GPU SM 中 Warp 调度与执行详解

## 概述

在 GPU 的 Streaming Multiprocessor (SM) 中，Warp 是调度和执行的基本单位。理解 Warp 的调度和执行机制是掌握 GPU 架构的关键。

---

## 1. Warp 基础概念

### 1.1 什么是 Warp？

**定义**：Warp 是一组并行执行的线程（threads），共享同一个程序计数器（PC）。

**本项目实现**：
```scala
// WarpContext 只保存调度和执行控制状态
class WarpContext(warpWidth: Int = 8, dw: Int = 32) extends Bundle {
  val pc             = UInt(8.W)
  val state          = WarpState()
  val activeMask     = UInt(8.W)
  val ctaId          = UInt(GpuParams.CTAIdWidth.W)
  val memWaitCounter = UInt(8.W)
  val started        = Bool()
}
```

通用寄存器值不在 `WarpContext` 中，而是存放在 SM 级 `SharedRegisterFile` 中：

```text
regs[warpId][laneId][regId]
```

因此，切换 warp 不会覆盖寄存器。`warp 0` 的 `R1` 和 `warp 1` 的 `R1` 是不同物理槽位；scheduler 切换 warp 时只是让 dispatcher 换一个 `warpId` 访问寄存器文件。

**真实 GPU**：
- NVIDIA GPU：Warp = 32 个线程
- AMD GPU：Wavefront = 64 个线程

### 1.2 为什么需要 Warp？

**SIMT (Single Instruction, Multiple Threads) 执行模型**：
- 一个 Warp 中的所有线程执行相同的指令
- 但每个线程操作不同的数据
- 类似 SIMD，但更灵活（支持分支）

**优势**：
- **简化控制逻辑**：只需一个 PC 和一个指令解码器
- **提高硬件利用率**：多个线程共享执行单元
- **隐藏内存延迟**：当一个 Warp 等待内存时，调度其他 Warp

---

## 2. Warp 调度器 (WarpScheduler)

### 2.1 调度器的职责

**核心任务**：每个周期从多个 Warp 中选择一个执行

**本项目实现**：
```scala
class WarpScheduler(numWarps: Int = 4) extends Module {
  val io = IO(new Bundle {
    val warpHalted = Input(Vec(numWarps, Bool()))  // 每个 Warp 的状态
    val grant      = Output(Vec(numWarps, Bool())) // 授予执行权限（one-hot）
    val allHalted  = Output(Bool())                // 所有 Warp 都已停止
  })
  
  // 轮询指针：指向下一次调度的起始位置
  val ptr = RegInit(0.U(log2Ceil(numWarps).W))
}
```

### 2.2 Round-Robin 调度策略

**算法流程**：

```
1. 从 ptr 指向的 Warp 开始查找
2. 找到第一个活跃（未 halted）的 Warp
3. 授予该 Warp 执行权限
4. 更新 ptr = (grantIdx + 1) % numWarps
5. 下一周期从新的 ptr 位置开始
```

**代码实现**：
```scala
// 构建活跃掩码
val active = VecInit((0 until numWarps).map(i => !io.warpHalted(i)))

// 旋转活跃掩码：将 ptr 位置移到索引 0
val rotated = VecInit((0 until numWarps).map(i =>
  active(((i.U +& ptr) % numWarps.U)(log2Ceil(numWarps) - 1, 0))
))

// 优先级编码器：找到第一个活跃的 Warp
val sel = PriorityEncoder(rotated.asUInt)

// 映射回原始索引
val grantIdx = ((sel +& ptr) % numWarps.U)(log2Ceil(numWarps) - 1, 0)

// 授予执行权限
io.grant := VecInit.fill(numWarps)(false.B)
when(!io.allHalted) {
  io.grant(grantIdx) := true.B
  ptr := ((grantIdx +& 1.U) % numWarps.U)(log2Ceil(numWarps) - 1, 0)
}
```

**调度示例**（4 个 Warp）：

```
初始状态：
  Warp 0: active
  Warp 1: active
  Warp 2: halted
  Warp 3: active
  ptr = 0

周期 1：
  - 从 Warp 0 开始查找 → 选中 Warp 0
  - 授予 Warp 0 执行权限
  - 更新 ptr = 1

周期 2：
  - 从 Warp 1 开始查找 → 选中 Warp 1
  - 授予 Warp 1 执行权限
  - 更新 ptr = 2

周期 3：
  - 从 Warp 2 开始查找 → 跳过 Warp 2（halted）→ 选中 Warp 3
  - 授予 Warp 3 执行权限
  - 更新 ptr = 0

周期 4：
  - 从 Warp 0 开始查找 → 选中 Warp 0
  - 循环继续...
```

### 2.3 调度策略的优势

**公平性**：
- 每个活跃 Warp 都有平等的执行机会
- 避免 Warp 饥饿（starvation）

**简单高效**：
- 只需一个寄存器（ptr）和简单的旋转逻辑
- 硬件开销小，延迟低

**延迟隐藏**：
- 当一个 Warp 等待内存时，调度器自动切换到其他 Warp
- 充分利用计算资源

---

## 3. Warp 执行流程

### 3.1 单个 Warp 的执行

**Warp 状态机**：
```scala
val sIdle :: sFetch :: sExecute :: sMemWait :: sHalted :: Nil = Enum(5)
val state = RegInit(sIdle)
```

**执行流程**：

```
1. Idle（空闲）
   - 等待调度器授予执行权限
   - 条件：scheduler.grant(warpId) == true

2. Fetch（取指）
   - 从 InstrMem 读取指令
   - 使用共享的 PC
   - 指令 = InstrMem[pc]

3. Decode（解码）
   - 解析指令操作码和操作数
   - opcode = instr[31:28]
   - rd, rs1, rs2, rs3, imm = instr[27:0]

4. Execute（执行）
   - 根据指令类型执行不同操作：
     a. 算术指令（ADD/MUL/MAD）：
        - 从 RegFile 读取源操作数
        - 4 个 CudaCore 并行计算
        - 结果写回 RegFile
     
     b. 内存指令（LD/ST）：
        - LD: 向 HBM Controller 发起读请求，进入 MemWait 状态
        - ST: 向 HBM Controller 发起写请求，请求被接受后完成
     
     c. 控制指令（HALT）：
        - 进入 Halted 状态

5. MemWait（内存等待）
   - 等待 HBM Model 返回 read response
   - 延迟计数器递减
   - 完成后返回 Idle 状态

6. Halted（停止）
   - Warp 执行完毕
   - 不再参与调度
```

**代码实现**：
```scala
class Warp extends Module {
  val io = IO(new Bundle {
    val grant = Input(Bool())  // 调度器授予的执行权限
    val imemData = Input(UInt(32.W))
    val hbmResp = Flipped(Valid(new HbmResponse(warpWidth, dw)))
    // ...
  })

  val state = RegInit(sIdle)
  val pc = RegInit(0.U(8.W))
  val memLatencyCnt = RegInit(0.U(8.W))

  switch(state) {
    is(sIdle) {
      when(io.grant && !io.halted) {
        state := sFetch
      }
    }

    is(sFetch) {
      // 读取指令
      val instr = io.imemData
      val opcode = instr(31, 28)
      
      // 解码并执行
      switch(opcode) {
        is(0x0.U) { // NOP
          pc := pc + 1.U
          state := sIdle
        }
        is(0x1.U) { // HALT
          state := sHalted
        }
        is(0x2.U) { // LD
          // 启动内存读取
          memLatencyCnt := memLatency.U
          state := sMemWait
        }
        is(0x4.U) { // ADD
          // 执行加法
          for (i <- 0 until warpWidth) {
            regFile(rd)(i) := regFile(rs1)(i) + regFile(rs2)(i)
          }
          pc := pc + 1.U
          state := sIdle
        }
        // ... 其他指令
      }
    }

    is(sMemWait) {
      when(memLatencyCnt === 0.U) {
        // 内存访问完成，写入寄存器
        for (i <- 0 until warpWidth) {
          regFile(rd)(i) := io.hbmResp.bits.rdata(i)
        }
        pc := pc + 1.U
        state := sIdle
      }.otherwise {
        memLatencyCnt := memLatencyCnt - 1.U
      }
    }

    is(sHalted) {
      // 保持停止状态
    }
  }

  io.halted := state === sHalted
}
```

### 3.2 多 Warp 并行执行

**SM 中的并行**：
```scala
class SM extends Module {
  val numWarps = 4
  val warps = Array.fill(numWarps)(Module(new Warp))
  val scheduler = Module(new WarpScheduler(numWarps))

  // 连接调度器和 Warp
  for (i <- 0 until numWarps) {
    scheduler.io.warpHalted(i) := warps(i).io.halted
    warps(i).io.grant := scheduler.io.grant(i)
  }
}
```

**时间线示例**（4 个 Warp，latency=10）：

```
程序：LD R0, [R15+0]  // 读取内存，延迟 10 周期
      ADD R1, R0, R0  // 加法
      HALT

周期 | Warp 0 | Warp 1 | Warp 2 | Warp 3 | 说明
-----|--------|--------|--------|--------|------
  1  | LD     | -      | -      | -      | Warp 0 启动内存读取
  2  | wait   | LD     | -      | -      | Warp 1 启动内存读取
  3  | wait   | wait   | LD     | -      | Warp 2 启动内存读取
  4  | wait   | wait   | wait   | LD     | Warp 3 启动内存读取
  5  | wait   | wait   | wait   | wait   | 所有 Warp 都在等待
  6  | wait   | wait   | wait   | wait   |
  7  | wait   | wait   | wait   | wait   |
  8  | wait   | wait   | wait   | wait   |
  9  | wait   | wait   | wait   | wait   |
 10  | wait   | wait   | wait   | wait   |
 11  | ADD    | wait   | wait   | wait   | Warp 0 内存完成，执行 ADD
 12  | HALT   | ADD    | wait   | wait   | Warp 0 停止，Warp 1 执行 ADD
 13  | -      | HALT   | ADD    | wait   | Warp 1 停止，Warp 2 执行 ADD
 14  | -      | -      | HALT   | ADD    | Warp 2 停止，Warp 3 执行 ADD
 15  | -      | -      | -      | HALT   | Warp 3 停止，所有 Warp 完成
```

**关键观察**：
- 周期 1-4：Warp 依次启动内存读取
- 周期 5-10：所有 Warp 都在等待内存（无计算）
- 周期 11-15：Warp 依次完成并停止
- **总周期**：15（如果只有 1 个 Warp，需要 12 周期）

**延迟隐藏效果有限**：
- 4 个 Warp 不足以完全隐藏 10 周期的内存延迟
- 真实 GPU 有 32-64 个 Warp，可以更好地隐藏延迟

---

## 4. SIMT 执行模型

### 4.1 SIMT vs SIMD

**SIMD (Single Instruction, Multiple Data)**：
- 所有数据元素必须执行相同的指令
- 不支持分支（或分支代价高）
- 例如：Intel SSE/AVX

**SIMT (Single Instruction, Multiple Threads)**：
- 每个线程有独立的 PC 和寄存器
- 支持分支（通过 predication 或 divergence）
- 更灵活，但硬件更复杂

### 4.2 分支处理（本项目未实现）

**问题**：Warp 中的线程执行不同的分支路径

```c
// CUDA 代码
if (threadIdx.x < 2) {
    result = a + b;  // 线程 0, 1 执行
} else {
    result = a * b;  // 线程 2, 3 执行
}
```

**解决方案 1：Predication（谓词执行）**：
```
所有线程都执行两个分支，但使用掩码控制写回：
  mask = (threadIdx < 2)
  temp1 = a + b
  if (mask) result = temp1
  
  mask = (threadIdx >= 2)
  temp2 = a * b
  if (mask) result = temp2
```

**解决方案 2：Divergence（分支发散）**：
```
1. 记录分支点和掩码
2. 先执行 then 分支（线程 0, 1 活跃）
3. 再执行 else 分支（线程 2, 3 活跃）
4. 合并回主路径
```

**性能影响**：
- 分支发散导致串行执行，降低并行度
- 最坏情况：Warp 中每个线程走不同路径，性能降低 32×

---

## 5. 内存访问模式

### 5.1 Coalesced Access（合并访问）

**定义**：Warp 中的线程访问连续的内存地址

**示例**：
```c
// 好的访问模式（合并）
for (int i = threadIdx.x; i < N; i += blockDim.x) {
    result[i] = data[i];  // 线程 0 访问 data[0]，线程 1 访问 data[1]，...
}
```

**本项目实现**：
```scala
// HBM-backed GlobalMem 访问：一个地址返回一行 warpWidth 宽的数据
val hbmAddr = rs1 + imm
io.hbmReq.bits.addr := hbmAddr
io.hbmReq.bits.wdata := laneData
```

**优势**：
- 一次内存事务可以服务整个 Warp
- 最大化内存带宽利用率

### 5.2 Strided Access（跨步访问）

**定义**：Warp 中的线程访问间隔的内存地址

**示例**：
```c
// 不好的访问模式（跨步）
for (int i = threadIdx.x; i < N; i += blockDim.x) {
    result[i] = data[i * stride];  // 线程访问不连续地址
}
```

**性能影响**：
- 需要多次内存事务
- 带宽利用率降低

---

## 6. 真实 GPU 的高级特性

### 6.1 更复杂的调度策略

**真实 GPU 调度器考虑**：
- **优先级**：不同 Warp 有不同优先级
- **年龄**：等待时间长的 Warp 优先
- **资源可用性**：寄存器、SharedMem 是否足够
- **指令类型**：优先调度计算密集型 Warp

**GigaThread Engine (NVIDIA)**：
- 支持数千个并发线程
- 动态负载均衡
- 抢占式调度（支持中断）

### 6.2 更多的 Warp

**真实 GPU**：
- 每个 SM 支持 32-64 个 Warp
- 总共 1024-2048 个并发线程
- 更好的延迟隐藏能力

**本项目**：
- 每个 SM 仅 4 个 Warp
- 总共 16 个并发线程
- 延迟隐藏能力有限

### 6.3 指令级并行

**真实 GPU**：
- 每个 SM 有多个执行单元：
  - INT32 ALU
  - FP32 ALU
  - FP64 ALU
  - Load/Store Unit
  - Special Function Unit (SFU)
- 可以同时执行多条不同类型的指令

**本项目**：
- 每个 Warp 只有 4 个 CudaCore
- 一次只能执行一种指令

---

## 7. 性能分析：用计数器观察延迟隐藏

GPU 隐藏访存延迟的核心不是让单次 `LD` 更快，而是让 `LD` 后等待数据的 warp 让出 issue 机会。只要 SM 中还有其它 `Ready` warp，调度器就继续发射其它 warp 的指令；只有所有 live warp 都在等待时，执行资源才真正因为访存而空转。

### 7.1 计数器定义

当前 GPU RTL 在 `GpuPerfCounters` 中暴露了以下教学用计数器：

| 计数器 | 含义 | 如何解读 |
|---|---|---|
| `totalCycles` | SM 至少有一个 active CTA 的周期数 | 程序总运行周期 |
| `activeWarpCycles` | `Ready + Stalled` live warp 的累计 warp-cycle | 平均 live warp 数 = `activeWarpCycles / totalCycles` |
| `eligibleWarpCycles` | 处于 `Ready` 状态的累计 warp-cycle | 调度器可选择的候选 warp 越多，越容易隐藏延迟 |
| `stalledWarpCycles` | 处于 `Stalled` 状态的累计 warp-cycle | 等待 global memory 的压力 |
| `noEligibleCycles` | live warp 存在但没有任何 `Ready` warp 的周期数 | 访存延迟没有被隐藏，SM 前端无 warp 可发射 |
| `aluIssueCycles` | 至少一个 ALU lane 发射的周期数 | 实际计算 issue |
| `sfuIssueCycles` | 至少一个 SFU lane 发射的周期数 | 特殊函数 issue |
| `memIssueCycles` | 发出 global memory 请求的周期数 | LD/ST 请求进入 memory path |
| `dualIssueCycles` | ALU 和 SFU 同周期发射的周期数 | 不同 warp 的 ALU/SFU 多发射效果 |

`stalledWarpCycles` 高不一定代表性能差；如果 `eligibleWarpCycles` 也高、`noEligibleCycles` 低，说明等待内存的 warp 大多被其它 ready warp 覆盖了。`noEligibleCycles` 才是“访存延迟暴露到 SM 前端”的直接信号。

### 7.2 验证命令

```bash
sbt "testOnly gpu.InstructionDispatcherMultiIssueTest gpu.DualSchedulerTest gpu.GpuIntegrationTest"
```

2026-05-03 的验证结果：9 个 GPU 相关测试全部通过。

### 7.3 实测结果

`gpu.DualSchedulerTest` 的单 SM 结果：

| 场景 | total | live warp-cycle | eligible | stalled | no-eligible | ALU issue | MEM issue |
|---|---:|---:|---:|---:|---:|---:|---:|
| 纯计算，10 条 ADD | 85 | 252 | 252 | 0 | 0 | 20 | 0 |
| 内存密集，`LD -> LD -> ADD -> ST`, `latency=10` | 104 | 318 | 131 | 187 | 11 | 4 | 12 |
| 混合，`LD -> ADD×5 -> ST`, `latency=5` | 79 | 282 | 224 | 58 | 0 | 20 | 8 |

`gpu.GpuIntegrationTest` 的 4-SM VADD 结果（下表报告最后完成的 SM；SM 之间会因共享 4-stack HBM 子系统的 stack 交织和 stack 内 bank/row 调度而不同）：

| 场景 | total | live warp-cycle | eligible | stalled | no-eligible | ALU issue | MEM issue |
|---|---:|---:|---:|---:|---:|---:|---:|
| VADD, `gmemLatency=1` | 105 | 312 | 144 | 168 | 12 | 4 | 12 |
| VADD, `gmemLatency=10` | 152 | 509 | 224 | 285 | 13 | 4 | 12 |

关键结论：

- 单 SM 内存密集用例中 `stalledWarpCycles=187`，说明 HBM 等待和请求排队都会形成可观停顿；`noEligibleCycles=11`，说明仍有部分等待暴露到前端。
- 4-SM VADD 中，latency=10 的总周期显著增加，主要来自多个 SM 共享 4-stack HBM 子系统后的 stack 交织、bank/row 时序和全局排队。
- 纯计算用例 `stalledWarpCycles=0`、`noEligibleCycles=0`，验证这些计数器没有把普通流水线开销误算成访存等待。
- 当前 `dualIssueCycles=0` 出现在这些 VADD/ADD/MEM 用例中是正常的；ALU/SFU 同周期发射由 `InstructionDispatcherMultiIssueTest` 单独验证。

### 7.4 和真实 GPU 的关系

真实 GPU 的同一思想更强：每个 SM 有更多 resident warp、更复杂的 scoreboard、更多 warp scheduler 和更多执行单元。访存延迟可能达到数百周期，但只要 occupancy 和 eligible warp 足够，scheduler 可以持续把其它 warp 的独立指令送进 ALU/SFU/LDST/Tensor 等单元。本项目用 4 个 warp 和可配置 `gmemLatency` 保留了这个核心机制，便于在波形和计数器中直接观察。

---

## 8. 总结

### 8.1 Warp 调度的关键点

1. **Round-Robin 策略**：
   - 简单、公平、高效
   - 使用轮询指针 ptr 实现
   - 避免 Warp 饥饿

2. **延迟隐藏**：
   - 当一个 Warp 等待内存时，调度其他 Warp
   - 需要足够多的 Warp 才能有效

3. **SIMT 执行**：
   - Warp 中的线程共享 PC
   - 支持分支，但有性能代价
   - 比 SIMD 更灵活

### 8.2 本项目 vs 真实 GPU

| 特性 | 本项目 | 真实 GPU | 差距 |
|------|--------|---------|------|
| **Warp 数量** | 4 | 32-64 | 8-16× |
| **Warp 宽度** | 4 线程 | 32 线程 | 8× |
| **调度策略** | Round-Robin | 优先级+年龄+资源 | 更复杂 |
| **指令级并行** | 无 | 多执行单元 | 更高效 |
| **分支处理** | 未实现 | Predication/Divergence | 支持 |

### 8.3 学习价值

**本项目展示了**：
- Warp 调度的基本原理
- Round-Robin 算法的实现
- 延迟隐藏的概念
- SIMT 执行模型

**真实 GPU 的额外复杂度**：
- 更多的 Warp 和更复杂的调度策略
- 分支处理和 predication
- 指令级并行和多执行单元
- 动态负载均衡和抢占式调度

---

## 参考资料

- 代码实现：`src/main/scala/gpu/WarpScheduler.scala`
- 代码实现：`src/main/scala/gpu/WarpContext.scala`
- 代码实现：`src/main/scala/gpu/SM.scala`
- 架构文档：`docs/architecture_zh.md` - 第二章
- 性能对比：`docs/performance_comparison.md`
