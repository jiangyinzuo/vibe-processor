# PE MAC 流水化教学记录

本文解释本项目为什么把 `PE` 内部 MAC 从单级表达改成两级流水，以及这会怎样影响 SystolicArray 的数据对齐、周期数和 Fmax 粗估。

## 原来的 PE 是什么样

旧版 `PE` 直接在一个寄存器前计算：

```text
psumOut = psumIn + weightReg * dataIn
```

Chisel 表达上很简洁，但综合后这一拍包含：

```text
dataIn/weightReg -> multiplier -> adder -> psumOut register
```

这对 toy 设计容易理解，但不够接近真实矩阵阵列。真实高吞吐 MAC 阵列通常不会把乘法和加法都塞在一个很长的组合路径里，而是通过流水寄存器把计算拆开。

## 新的两级 MAC 流水

当前 `PE` 拆成两级：

```text
stage 0: productReg := weightReg * dataIn
         psumReg    := psumIn

stage 1: psumOutReg := psumReg + productReg
```

代码位于 `src/main/scala/ascend/PE.scala`。

这样每个 PE 的 vertical partial sum 延迟从 1 cycle 变成 2 cycles。也就是说，psum 从阵列第 0 行传到第 1 行需要 2 拍，而不是 1 拍。

## 为什么 SystolicArray 也要改

SystolicArray 的核心要求是：某个输出元素 `C[i][j]` 的 partial sum 往下走到第 `k` 行时，必须遇到同一个 `i` 对应的 activation `A[i][k]`。

旧版 PE 的 psum 行间延迟是 1 拍，所以 activation row skew 是：

```text
actIn(k) = A[feedCnt - k][k]
```

现在 PE 的 psum 行间延迟是 2 拍，所以 activation 必须晚一点送到更低的行：

```text
actIn(k) = A[feedCnt - k * PeMacLatency][k]
```

其中：

```text
PeMacLatency = 2
```

因此 16x16 tile 的有效注入周期从普通 `2N - 1 = 31` 变成：

```text
feedCycles = N + (N - 1) * PeMacLatency
           = 16 + 15 * 2
           = 46
```

这就是本次测试里 `cubeComputeCycles = 46` 的来源。

## 结果什么时候出现

旧版注释里结果出现时间近似为：

```text
C[i][j] at absCycle = i + j + N
```

现在 psum 每下移一行需要 2 拍，所以改成：

```text
C[i][j] at absCycle = i + j + N * PeMacLatency
```

`SystolicArray.scala` 里的 result capture 逻辑也按这个公式更新。

## 本次代码改动

| 文件 | 变更 |
|---|---|
| `src/main/scala/ascend/AscendParams.scala` | 新增 `PeMacLatency = 2` |
| `src/main/scala/ascend/PE.scala` | 新增 `productReg`、`psumReg`、`psumOutReg`，把 MAC 拆成乘法/加法两级 |
| `src/main/scala/ascend/SystolicArray.scala` | 按 `PeMacLatency` 更新 feed 周期和结果捕获公式 |
| `src/main/scala/ascend/CubeUnit.scala` | 按 `PeMacLatency` 更新 activation skew |
| `src/test/scala/ascend/PETest.scala` | 更新 PE 结果延迟检查 |
| `src/test/scala/ascend/SystolicArrayTest.scala` | 更新 skew 生成和 drain 等待上限 |

## 功能周期效果

PE 流水化会增加每个 tile 的可见计算窗口，因为每一行的 psum 传播更慢。结合本次 16x16 tile，单次 MATMUL 的有效 Cube 注入周期为 46。

| 指标 | 旧 8x8 单级 PE | 新 16x16 两级 PE |
|---|---:|---:|
| tile MAC 数 | 512 | 4096 |
| cubeComputeCycles | 15 | 46 |
| Cube 有效 MAC/cycle | 34.13 | 89.04 |
| totalCycles | 182 | 349 |
| total MAC/cycle | 2.81 | 11.74 |

这个表不能把所有变化都归因于 PE 流水化，因为 tile 同时从 8x8 扩到了 16x16。正确解读是：

- 16x16 tile 提高了每次 MATMUL 的工作量。
- 两级 PE 让 psum 行间传播从 1 拍变成 2 拍，因此计算窗口从 31 拍扩展到 46 拍。
- 即使计算窗口变长，单 tile 的有效 MAC/cycle 仍比旧 8x8 模型高。

## Fmax 粗估效果

命令：

```bash
REPORT_CSV=/tmp/npu_fractal_pe_fmax.csv scripts/estimate_fmax.sh PE SystolicArray CubeUnit CubeCore AiCore
```

环境同项目其它 STA 文档：Yosys + OpenSTA，sky130 slow corner，post-synthesis / pre-layout 粗估。

| 模块 | critical path | Fmax |
|---|---:|---:|
| `PE` | 16.290 ns | 61.39 MHz |
| `SystolicArray` | 122.973 ns | 8.13 MHz |
| `CubeUnit` | 195.486 ns | 5.12 MHz |
| `CubeCore` | 526.906 ns | 1.90 MHz |
| `AiCore` | 525.216 ns | 1.90 MHz |

为了单独看 PE 流水化，使用旧版单周期 PE 在临时 worktree 上跑同样的 `PE` 独立 STA：

```text
旧版 PE: 16.290 ns, 61.39 MHz
新版 PE: 16.290 ns, 61.39 MHz
```

结论要如实看：

- 在这个粗糙综合流里，单个 PE 的独立 Fmax 没有改善；乘法器本身仍是主要路径。
- 两级流水仍然有教学意义：它把 PE 的时序模型从“一拍乘加”改成更接近真实阵列的流水传播模型。
- 系统级 Fmax 反而下降，是因为阵列从 8x8 扩到 16x16 后，`CubeCore` / `CubeUnit` 的展开寄存器、tile mux、控制扇出大幅增加。
- 下一步若要提系统 Fmax，重点不是继续改单个 PE，而是继续切 `CubeCore -> CubeUnit -> SystolicArray` 的 16x16 数据选择、输入 skew 生成和 result capture 路径。

## 设计取舍

这次修改牺牲了一部分可见 compute cycles，换来两个目标：

1. PE 内部不再假设乘法和加法同拍完成。
2. 阵列时序模型显式包含 `PeMacLatency`，后续可以继续扩展到更深 MAC pipeline。

它不是最终高频实现。当前更准确的说法是：我们把模型推进到“真实阵列流水语义”的下一步，并暴露出 16x16 Cube tile 下真正需要优化的系统级路径。
