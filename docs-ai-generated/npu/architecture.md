# NPU 整体架构

```text
ToyAscendTop
  ├── ControlCpu
  │     └── SpmdBlockScheduler
  ├── AiCpu
  ├── InstrMem
  ├── L2 Buffer
  ├── HBM Controller
  ├── HBM Model（仿真用）
  ├── AiCore 0 + private UB
  └── AiCore 1 + private UB
```

当前顶层默认包含 2 个物理 AiCore：

`ControlCpu` 负责启动和分配 SPMD logical block。它内部复用 `SpmdBlockScheduler`，把 `blockIdx` 发给空闲的物理 AiCore。物理核执行完当前 block 并 `HALT` 后，可以继续领取下一个 block，直到完成 `blockDim` 个逻辑 block。

`InstrMem` 是多核共享的指令存储。每个 AiCore 有独立 PC，可以执行同一段程序但处在不同进度上。L2 Buffer 也是多核共享的片上存储；UB 是每个 AiCore 私有的本地 buffer。

`AiCpu` 在当前顶层中已经实例化，但还没有接入完整的顶层任务队列。它单独建模 device 侧 CPU-like 辅助执行单元，支持对 L2 行数据做 `FILL`、`COPY`、`ADD_IMM` 这类简单任务。SPMD block 调度不放在 `AiCpu` 里，而是放在 `ControlCpu`，这样职责更清楚。

## 多核架构

## AI Core

每个 `AiCore` 是一个执行 slot，内部结构如下：

```text
AiCore
  ├── ScalarUnit
  ├── CubeCore
  │     └── CubeUnit
  │           └── SystolicArray 16x16
  ├── VectorCore
  │     └── VectorUnit
  ├── MTE1: UB -> Cube input FIFO
  ├── MTE2: L2 <-> UB
  └── MTE3: L0C -> UB
```

`ScalarUnit` 做取指、译码和调度。它不直接搬数据，而是向三条队列发 task：

| 队列 | 消费者 | 数据方向 | 来源指令 |
|---|---|---|---|
| `CopyInQueue` | `MTE1` | UB -> Cube input FIFO | `LOAD` |
| `DMAQueue` | `MTE2` | L2 <-> UB | `DMA_LOAD` / `DMA_STORE` |
| `CopyOutQueue` | `MTE3` | L0C -> UB | `STORE` |

`MATMUL` 和向量指令仍是阻塞指令：Scalar 发起后等待 `CubeCore` 或 `VectorCore` 完成。搬运类指令是非阻塞入队，后续通过 `WAIT` 指令明确同步边界。

### CubeCore

`CubeCore` 是矩阵计算路径，主要包含：

- activation tile FIFO：代码内部写作 `l0a`。
- weight tile FIFO：代码内部写作 `l0b`。
- L0C：保存输出 tile，也可作为 K 方向分块累加的 partial sum buffer。
- `CubeUnit`：真正启动 16x16 systolic array。

当前 tile 大小是 16x16，数据路径是 INT8 输入、INT32 累加。`MATMUL` 支持两种写回模式：

```text
accumulate = 0: L0C := L0A * L0B
accumulate = 1: L0C := L0C + L0A * L0B
```

activation/weight 两个 FIFO 各有 4 个 tile slot。`MTE1` 写满一组 activation 和 weight 后，对应 slot 变为 ready。`MATMUL` 到达时，`CubeCore` 从 ready slot 启动 `CubeUnit`。启动时会把两个输入 tile 快照到 Cube 输入寄存器，后续 systolic array 计算期间输入保持稳定。

`SystolicArray` 是 weight-stationary 结构。每个 PE 内部把乘法和加法拆成两级流水，`PeMacLatency=2`。16x16 tile 的有效 activation feed 窗口是：

```text
N + (N - 1) * PeMacLatency = 16 + 15 * 2 = 46 cycles
```

### VectorCore

`VectorCore` 走 UB 路径，不复用 Cube 的本地输入 FIFO 和 L0C。当前支持：

- `VECADD`：两个 N-wide INT32 vector 相加。
- `RELU`：对一个 N-wide INT32 vector 做 `max(0, x)`。

这里的 N 跟 Cube tile 边长一致，默认是 16。`VectorCore` 从 UB 读源操作数，`VectorUnit` 计算完成后把结果写回第一个源地址。

### AI CPU

真实昇腾系统里，AI CPU 更像 device 侧 CPU 资源，用于处理不适合 Cube 的控制密集、分支密集或普通内存任务。本项目只保留这个角色，不实现 ARM ISA。

当前 `AiCpu` 支持三类 L2 row task：

| 操作 | 含义 |
|---|---|
| `FILL` | 用立即数填充若干行 L2 |
| `COPY` | 从 L2 一段地址复制到另一段地址 |
| `ADD_IMM` | 读 L2 行，加立即数后写回目标地址 |

顶层目前把 `AiCpu` 命令输入置空，`AiCpuTest` 会直接测试这个模块。

## SPMD 编程模型

顶层启动接口包含 `start` 和 `blockDim`。`blockDim` 表示逻辑 block 数，`blockDim=0` 时默认等于物理核数，兼容早期两核测试。

每个物理 AiCore 运行时会拿到一个 `blockIdx`。当前 ISA 没有把 `blockIdx` 暴露成 scalar 可读寄存器，而是在 DMA 地址路径里自动使用它：

```text
effectiveL2 = encodedL2Base + blockIdx * blockStride
```

默认 `blockStride = L2SliceSize = 1024`。测试也可以在构造 `ToyAscendTop` 时传入更小的 stride，用 2 个物理核执行更多逻辑 block。

这个模型表达的是 1D SPMD 子集：

- 同一段 kernel 程序在多个 logical block 上重复执行。
- 不同 block 通过 `blockIdx * blockStride` 访问不同 L2 数据切片。
- 物理 AiCore 数量只是执行资源数量，不等于 logical block 数量上限。

## 存储层次结构

当前 AiCore 运行时数据路径是：

```text
L2 -> MTE2 -> UB -> MTE1 -> Cube input FIFO -> Cube -> L0C -> MTE3 -> UB -> MTE2 -> L2
```

各层配置来自 `AscendParams`：

| 层级 | 当前实现 | 容量/形状 | 共享范围 |
|---|---|---:|---|
| HBM Controller | `HbmController` | 请求/响应边界 | 顶层全局 |
| HBM Model | `HbmModel` 内部包裹 `LatencyMem` | 4096 行，默认 10 cycle latency | 仿真外部存储 |
| L2 Buffer | `Mem` | 2048 行 | 多 AiCore 共享 |
| UB | `UnifiedBuffer` | 每核 256 行 | AiCore 私有 |
| L0 activation FIFO | `CubeCore.l0a` | 4 个 16x16 tile slot | CubeCore 私有 |
| L0 weight FIFO | `CubeCore.l0b` | 4 个 16x16 tile slot | CubeCore 私有 |
| L0C | `CubeCore` 内部寄存器 | 1 个 16x16 tile | CubeCore 私有 |

L2 带有外部 preload/readback 端口，服务测试。HBM 路径现在拆成 `HbmController` 和 `HbmModel`：controller 表示计算 die 侧的控制器边界，model 表示仿真环境中的外部 HBM stack。当前 AiCore 实际执行路径只访问 L2，不直接访问 HBM；`ToyAscendTop` 里 `HbmController` 的 core-facing request 口仍置为空闲。

UB 有两个端口：

- Port A：`MTE1`、`MTE3`、`VectorCore` 仲裁共享。
- Port B：`MTE2` 独占，用于 L2 与 UB 之间搬运。

这个端口划分会影响可重叠性。比如 `MTE2` 可以和 `MTE1`/`MTE3` 在不同 UB 端口上并行；但 `MTE1`、`MTE3` 和 `VectorCore` 之间不能同时占用 Port A。

## 数据搬运

数据搬运分成三条 MTE 路径：

### MTE2：L2 和 UB 之间

`DMA_LOAD`、`DMA_STORE` 进入 `DMAQueue`。队列非空且 `MTE2` 空闲时，AiCore 发起一次 MTE2 task。

- `DMA_LOAD`：L2 连续 N 行写入 UB。
- `DMA_STORE`：UB 连续 N 行写回 L2。
- L2 地址自动叠加 `blockIdx * blockStride`。

### MTE1：UB 到 Cube input FIFO

`LOAD` 进入 `CopyInQueue`。`MTE1` 从 UB 逐行读出 N 行数据，截取为 INT8 后写入 `CubeCore` 的 activation FIFO 或 weight FIFO。

这里有一个命名细节：ISA 中 `L0_A` 表示权重缓存、`L0_B` 表示激活缓存；`CubeCore.l0a/l0b` 是当前 RTL 内部寄存器名，不要直接按 ISA selector 名称反推。阅读指令行为时以 `docs/npu/isa.md` 的 ISA 语义为准。

### MTE3：L0C 到 UB

`STORE` 进入 `CopyOutQueue`。`MTE3` 从 L0C 逐行读取 INT32 结果，写回 UB，后续通常再用 `DMA_STORE` 写回 L2。

### WAIT token

`WAIT` 指令通过 bit `[27:26]` 选择等待对象：

| selector | 名称 | 等待对象 |
|---:|---|---|
| 0 | `WAIT_ALL` | CopyIn、DMA、CopyOut 全部清空 |
| 1 | `WAIT_DMA` | `DMAQueue` 和 `MTE2` 完成 |
| 2 | `WAIT_COPY_IN` | `CopyInQueue` 和 `MTE1` 完成 |
| 3 | `WAIT_COPY_OUT` | `CopyOutQueue` 和 `MTE3` 完成 |

这种同步粒度比旧式“等所有 DMA 完成”更适合表达 tile pipeline。例如，下一次 `DMA_LOAD` 要复用 UB 空间时，程序只需要 `WAIT_COPY_IN` 确认 MTE1 已经把旧 tile 读入 L0；不必等待正在进行的 CopyOut 或其它无关搬运。

## 性能计数

每个 AiCore 输出一组 `PerfCounters`。主要用于观察三类问题：

- Scalar 是否在等 Cube、Vector 或 MTE。
- Cube 有效计算窗口有多长。
- MTE 搬运是否和 Cube 计算发生重叠。

常看的计数包括：

| 计数器 | 含义 |
|---|---|
| `totalCycles` | block 从 start 到 halted 的周期 |
| `cubeTotalCycles` / `cubeComputeCycles` | Cube 总活跃周期 / systolic array feed 周期 |
| `dmaTotalCycles` | MTE2 执行周期 |
| `copyInCycles` / `copyOutCycles` | MTE1 / MTE3 执行周期 |
| `dataflowOverlapCycles` | Cube 与任一 MTE 同时活跃的周期 |
| `waitAllCycles` / `waitDmaCycles` / `waitCopyInCycles` / `waitCopyOutCycles` | 不同 WAIT 造成的停顿 |
| `blockStarts` / `blockCompletions` | SPMD block 启动和完成次数 |

当前 `PROJECT_STATUS.md` 记录了最近一次代表性结果：单 tile MATMUL 总计 347 cycles，其中 Cube compute 46 cycles、MTE2 DMA 144 cycles、CopyIn 98 cycles、CopyOut 16 cycles。

## 当前简化点

这个 NPU 模型有意保留了很多简化：

- 只支持 1D `blockIdx`，没有 2D/3D grid。
- `GetBlockIdx()` 没有作为 scalar 指令或寄存器暴露。
- HBM Controller 尚未接入真实 GM 到 L2 的运行时搬运路径；`HbmModel` 仅用于仿真 preload/readback。
- L2、UB、L0 没有建模 bank conflict、NoC、cache coherence 或复杂 arbitration。
- MTE 队列是简单 FIFO，没有 stream、barrier、重排序和真实格式转换。
- AI CPU 只是 L2 row task engine，不是完整 CPU。
- Cube 使用 16x16 systolic array 和 INT8/INT32 数据类型，但没有复刻真实昇腾 Cube 的全部数据格式、指令和异常行为。

这些简化的目的，是让项目可以用 ScalaTest 直接验证核心数据流，并用性能计数器看到“计算单元是否被数据喂饱”。
