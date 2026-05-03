# 教程：用 event/token 做细粒度同步

真实昇腾式 NPU 不应该把同步理解成“等所有 DMA 都结束”。更贴近硬件和编译器的方式是：每个数据流阶段完成一个 tile 或一批 task 后，产生一个可以被后续阶段消费的 event/token。消费者只等待自己真正依赖的 token。

## 为什么全局等待不够

旧模型里只有一个粗粒度 `DMA_WAIT`：

```text
DMA_LOAD A/W -> DMA_WAIT -> LOAD -> MATMUL -> STORE -> DMA_STORE -> DMA_WAIT
```

这能保证正确，但会把本来可以并行的阶段串起来。比如 `MATMUL(tile0)` 只依赖 tile0 已经在 L0A/L0B 中，并不依赖 tile1 的 CopyOut，也不依赖后续所有 DMA 都清空。

细粒度同步要回答的是：

```text
我要等的是哪一个生产者？
生产者完成后，哪个消费者才可以继续？
```

本项目现在把 `WAIT` 拆成四类 token：

| WAIT selector | 等待对象 | 典型含义 |
|---|---|---|
| `WAIT_ALL` | CopyIn + DMA + CopyOut 全部空闲 | kernel 收尾 |
| `WAIT_DMA` | MTE2 的 L2<->UB task 完成 | UB 数据可以被 MTE1/Vector 读取 |
| `WAIT_COPY_IN` | MTE1 的 UB->L0A/L0B task 完成 | L0 tile 已经 ready，UB 可安全复用 |
| `WAIT_COPY_OUT` | MTE3 的 L0C->UB task 完成 | UB 结果可被 DMA_STORE 读走 |

编码仍然复用 opcode `0xA`：

```text
WAIT: [31:28] opcode=0xA  [27:26] wait selector  [25:0] reserved
```

## 硬件怎么实现 token

`AiCore` 内部对每条数据流维护 pending 条件：

```text
copyInPending  = CopyInQueue非空  或 MTE1 busy/done
dmaPending     = DMAQueue非空     或 MTE2 busy/done
copyOutPending = CopyOutQueue非空 或 MTE3 busy/done
```

`ScalarUnit` 执行 `WAIT` 时不会再看一个全局 DMA 空队列，而是根据 selector 选择对应 pending：

```text
WAIT_DMA      -> 等 dmaPending == false
WAIT_COPY_IN  -> 等 copyInPending == false
WAIT_COPY_OUT -> 等 copyOutPending == false
WAIT_ALL      -> 等三类 pending 全部为 false
```

这就是 toy 版 token scoreboard。它不是乱序执行，也不是 cache miss 自动隐藏，而是让程序/编译器明确表达“这条消费者指令依赖哪条生产者数据流”。

## 程序怎么使用

单 tile 的安全写法：

```asm
DMA_LOAD  ub=0,  l2=0
DMA_LOAD  ub=16, l2=16
WAIT_DMA             ; MTE2 已把 A/W 放进 UB

LOAD      L0_B, 0
LOAD      L0_A, 16
MATMUL                ; CubeCore 会等 L0A/L0B tile ready 后启动

STORE     L0_C, 32
WAIT_COPY_OUT         ; MTE3 已把 L0C 写入 UB[32..]
DMA_STORE ub=32, l2=32
WAIT_ALL
HALT
```

流水线写法的关键是不要等待无关阶段：

```asm
; tile0 进入 L0，随后 UB 可以复用
DMA_LOAD tile0_A
DMA_LOAD tile0_W
WAIT_DMA
LOAD tile0_A_to_L0
LOAD tile0_W_to_L0
WAIT_COPY_IN

; tile1 预取可以和 tile0 计算重叠
DMA_LOAD tile1_A
DMA_LOAD tile1_W
WAIT_DMA
LOAD tile1_A_to_L0
LOAD tile1_W_to_L0
MATMUL tile0

STORE tile0_C
WAIT_COPY_OUT
DMA_STORE tile0_C
```

这里 `WAIT_COPY_IN` 的意义不是“计算必须等它”，而是“同一段 UB 即将被下一次 DMA_LOAD 覆盖，所以必须确认 MTE1 已经读完”。这是教学项目里最容易忽略的真实硬件问题：**同步不仅保护计算正确性，也保护片上 buffer 复用正确性**。

## 优化效果

当前 16x16 Cube tile、PE 两级 MAC 流水、三条 MTE task queue 下，NPU 测试的代表性结果如下：

| 场景 | totalCycles | Cube compute | MTE2 DMA | CopyIn | CopyOut | dataflow overlap |
|---|---:|---:|---:|---:|---:|---:|
| 单 tile MATMUL | 347 | 46 | 144 | 98 | 16 | 94 |
| Pipeline3 3 tile | 855 | 138 | 432 | 294 | 48 | 182 |
| TripleBuffer 4 tile | 1129 | 184 | 576 | 392 | 64 | 250 |
| OverlapBenchmark 顺序版 | 1038 | 138 | 432 | 294 | 48 | 282 |
| OverlapBenchmark 流水版 | 832 | 138 | 432 | 294 | 48 | 282 |

解读：

- `WAIT_DMA`、`WAIT_COPY_IN`、`WAIT_COPY_OUT` 让程序能在正确边界等待，而不是所有阶段一起等。
- Pipeline3 和 TripleBuffer 仍然能让 CopyIn/DMA 与 Cube 重叠，但由于 MTE2 搬运 432/576 cycles 明显长于 Cube compute 138/184 cycles，访存延迟还没有被充分隐藏。
- OverlapBenchmark 流水版从 1038 cycles 降到 832 cycles，说明更早发出下一 tile 的数据流任务确实减少总时间。

## 教学结论

真实昇腾设计思想里，event/token 是软件流水和片上 buffer 复用的契约。Cube 不是自己猜数据何时 ready；编译器/程序通过 CopyIn、Compute、CopyOut 和 event wait 把依赖关系显式写出来。这个 toy NPU 现在用最小 scoreboard 表达了这件事：**等待要等具体 token，而不是粗暴等待整个世界安静下来**。
