# 双 Warp Scheduler

本文记录当前 toy GPU 的双调度器模型和性能计数器含义。

## 架构

每个 SM 有 2 个 `SMSubPartition`：

```text
SM
├── SMSubPartition 0: Scheduler 0, Warp 0-1
├── SMSubPartition 1: Scheduler 1, Warp 2-3
├── SharedRegisterFile
└── InstructionDispatcher
```

每个 scheduler 只在本分区内做 round-robin 选择。被选中的 ready warp 通过共享 dispatcher 访问寄存器文件、CudaCore/SFU 和内存路径。

## 性能计数

### 纯计算程序

| 指标 | 数值 |
|---|---:|
| total cycles | 85 |
| live warp cycles | 252 |
| eligible warp cycles | 252 |
| stalled warp cycles | 0 |
| no-eligible cycles | 0 |
| ALU issue cycles | 20 |

`stalledWarpCycles=0` 和 `noEligibleCycles=0` 说明该用例没有访存等待。

### 内存密集程序，`gmemLatency=10`

| 指标 | 数值 |
|---|---:|
| total cycles | 113 |
| live warp cycles | 334 |
| eligible warp cycles | 128 |
| stalled warp cycles | 206 |
| no-eligible cycles | 23 |
| MEM issue cycles | 12 |

4 个 warp 共发出 `2 x LD + 1 x ST`，因此 `MEM issue cycles = 12`。`stalledWarpCycles=206` 同时包含 HBM model 读延迟和 HBM Controller backpressure，`noEligibleCycles=23` 表示仍有 23 个周期没有 ready warp 可发射。

### 混合程序，`gmemLatency=5`

| 指标 | 数值 |
|---|---:|
| total cycles | 75 |
| live warp cycles | 266 |
| eligible warp cycles | 220 |
| stalled warp cycles | 46 |
| no-eligible cycles | 0 |
| ALU issue cycles | 20 |
| MEM issue cycles | 8 |

该用例计算密度更高，访存等待没有形成完整的 no-eligible 前端气泡。

## 指标解释

```text
warp occupancy = liveWarpCycles / (totalCycles x residentWarps)
ready coverage = eligibleWarpCycles / (totalCycles x residentWarps)
noEligibleCycles = 有 live warp 但没有 ready warp 的周期
```

- `liveWarpCycles` 表示 resident warp 的活跃覆盖。
- `eligibleWarpCycles` 表示 scheduler 是否有可发射对象。
- `stalledWarpCycles` 表示访存压力。
- `noEligibleCycles` 表示无法隐藏的前端气泡。

## 与真实 GPU 的差距

| 维度 | 本项目 | 真实 GPU |
|---|---:|---:|
| scheduler/SM | 2 | 通常 4 或更多 |
| resident warp/SM | 4 | 数十个 |
| warp width | 4 | 32 |
| 资源仲裁 | 简单优先级 | scoreboard、bank、issue port、memory pipeline 协同 |

本项目保留多 scheduler、资源仲裁和延迟隐藏这三个核心概念，但没有建模完整 scoreboard、分支发散、coalescing 和真实执行端口结构。

## 测试

```bash
sbt "testOnly gpu.DualSchedulerTest"
sbt "testOnly gpu.GpuIntegrationTest"
```

相关源码：

- `src/main/scala/gpu/SM.scala`
- `src/main/scala/gpu/SMSubPartition.scala`
- `src/main/scala/gpu/WarpScheduler.scala`
- `src/test/scala/gpu/DualSchedulerTest.scala`
