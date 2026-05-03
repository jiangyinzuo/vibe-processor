# GPU 架构对比：独占执行单元与共享执行单元

本文记录 GPU 模型从早期“每个 Warp 独占 CUDA Core”到当前“SM 共享 CUDA Core”的架构差异。

## 早期模型的问题

早期结构将 CUDA Core 放在 Warp 模块内部：

```text
SM
├── WarpScheduler
├── Warp[0]
│   ├── CudaCore[0..warpWidth-1]
│   └── RegFile
├── Warp[1]
│   ├── CudaCore[0..warpWidth-1]
│   └── RegFile
└── ...
```

该结构便于初学者理解单个 warp 的执行，但与真实 GPU 不一致：

- 物理 ALU 被绑定到 warp，上下文数量增加会线性增加执行单元。
- 每周期只有少数 warp 被调度，其余 warp 内的 ALU 闲置。
- Warp 被误建模为“带计算单元的硬件块”，而不是轻量执行上下文。

## 当前模型

当前 GPU 模型采用 SM 级共享执行资源：

```text
SM
├── SMSubPartition[0..1]
│   ├── WarpScheduler
│   └── lane 执行单元
├── SharedRegisterFile
├── InstructionDispatcher
├── SharedMem
└── GlobalMem 接口
```

Warp 主要保存：

```text
PC
state: Ready / Stalled / Halted
memory wait state
thread/CTA coordinates
```

指令由 scheduler 选择 ready warp，再由 dispatcher 读取寄存器并发送到共享执行单元。

## 对比

| 维度 | 早期独占模型 | 当前共享模型 | 真实 GPU |
|---|---|---|---|
| CUDA Core 归属 | 每个 Warp 独占 | SM/sub-partition 共享 | SM 分区共享 |
| Warp 含义 | 包含计算单元 | 执行上下文 | 执行上下文 |
| 寄存器 | 每 Warp 局部 | SharedRegisterFile | 大容量分区寄存器文件 |
| 调度 | 选择一个带 ALU 的 Warp | 选择 ready warp 后分发 | 多 scheduler + scoreboard |
| 教学价值 | 结构简单 | 更接近真实资源共享 | 真实目标 |

## 设计原则

真实 GPU 将昂贵执行单元放在 SM 中共享，用大量 warp 提供可切换的执行上下文。这样在某个 warp 等待内存或长延迟单元时，scheduler 可以发射其它 ready warp。

本项目当前保留了该原则的简化版本：

- CUDA Core 不再属于单个 warp。
- Warp context 与执行单元解耦。
- `eligibleWarpCycles`、`stalledWarpCycles` 和 `noEligibleCycles` 用于观察延迟隐藏是否生效。

## 当前限制

- Warp 数、warp 宽度和 scheduler 数量远小于真实 GPU。
- Scoreboard、寄存器 bank conflict、分支发散和真实 coalescing 仍未完整建模。
- Tensor Core 未建模，不能用当前 GPU 模型与 NPU Cube 做矩阵单元峰值对比。

相关文档：

- [共享架构重构](shared_architecture_summary.md)
- [Warp 调度](warp_scheduling.md)
- [GPU 架构](architecture.md)
