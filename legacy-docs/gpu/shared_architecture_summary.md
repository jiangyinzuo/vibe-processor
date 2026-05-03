# GPU 共享执行资源模型

本文记录 GPU 模型从每个 warp 独占执行单元改为 SM 共享执行资源后的结构。

## 动机

早期模型把 CUDA Core 和寄存器文件放在每个 Warp 内部。该结构简单，但会把 warp 误表达为“拥有物理 ALU 的硬件块”。真实 GPU 中，warp 是执行上下文，CUDA Core、SFU、load/store 等执行资源属于 SM 或 SM 分区。

## 当前结构

### WarpContext

`WarpContext` 保存轻量状态：

```scala
state
pc
memWaitCounter
memRdData
```

它不包含 ALU 或私有寄存器文件。

### SharedRegisterFile

`SharedRegisterFile` 集中保存所有 warp 的寄存器：

```scala
regs(warpId)(regId)(laneId)
```

dispatcher 通过 `warpId` 选择对应上下文的寄存器。

### InstructionDispatcher

dispatcher 负责：

- 读取 selected warp 的指令。
- 读取源寄存器。
- 向 CudaCore、SFU 或 memory path 发射。
- 处理写回和写端口冲突。

### SMSubPartition

每个 sub-partition 拥有一个 scheduler 和一组 lane 执行单元。两个 sub-partition 共同构成当前 SM 的双调度器模型。

## 关键修复

### 内存数据缓冲

连续 LOAD 曾经因为组合读数据被后续地址覆盖。当前在 `WarpContext.memRdData` 中保存返回数据，再进入写回。

### 写端口冲突

当内存写回与算术写回可能同周期发生时，dispatcher 需要仲裁，避免同一周期覆盖目标寄存器。

## 资源利用率

| 架构 | CUDA Core 数量 | 同周期可活跃 warp | 说明 |
|---|---:|---:|---|
| 早期独占模型 | `numWarps x warpWidth` | 1 | 大部分 ALU 闲置 |
| 当前共享模型 | `numSubPartitions x warpWidth` | 最多 2 | 更接近 SM 分区共享 |

## 测试

常用验证：

```bash
sbt "testOnly gpu.GpuIntegrationTest gpu.DualSchedulerTest"
```

相关源码：

- `src/main/scala/gpu/WarpContext.scala`
- `src/main/scala/gpu/SharedRegisterFile.scala`
- `src/main/scala/gpu/InstructionDispatcher.scala`
- `src/main/scala/gpu/SMSubPartition.scala`
- `src/main/scala/gpu/SM.scala`
