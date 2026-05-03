# Cube 分形 Tile 格式教学记录

本文解释本项目为什么把 Cube tile 从普通 8x8 行列块升级为 16x16 分形 tile 抽象，以及这种布局为什么更接近昇腾 NPU 的 Cube 编程模型。

## 分形格式是什么

普通矩阵通常按 row-major 保存：

```text
A[0][0], A[0][1], A[0][2], ...
A[1][0], A[1][1], A[1][2], ...
```

这种布局对 CPU 顺序扫一行很友好，但 Cube 不是一个标量 ALU。Cube 每次消费的是一个矩阵 tile，L0A/L0B 需要按固定宽度把一组元素喂给矩阵阵列。分形格式的思想是：先把大矩阵切成固定大小的小块，再让每个小块连续存放。

本项目采用教学版 block-fractal 布局：

```text
logical matrix
  -> split into 16x16 tiles
  -> each tile is stored contiguously
  -> tail rows/cols are padded with zero
```

逻辑坐标 `(row, col)` 被拆成四个部分：

```text
tileRow = row / 16
tileCol = col / 16
innerRow = row % 16
innerCol = col % 16
```

在 packed buffer 里的位置是：

```text
flatIndex =
  (((tileRow * tilesPerRow + tileCol) * 16 + innerRow) * 16) + innerCol
```

代码位于 `src/main/scala/ascend/FractalTile.scala`。它是软件侧教学 helper，用来说明 host/compiler 如何把普通矩阵 pack 成 Cube 喜欢的 tile 序列。硬件侧仍然一次接收一个 16x16 tile，内部 `Vec(n, Vec(n, ...))` 保持教学上容易读的二维表达。

## 为什么要用分形格式

### 1. 匹配 Cube 的取数粒度

Cube 的计算单元不是按单个元素随取随算，而是按 tile 做矩阵乘。分形格式把同一个 16x16 tile 的数据放在连续区域里，MTE 搬运、L0A/L0B 写入、Cube input snapshot 都能按固定块工作。

如果仍使用大矩阵 row-major，跨 tile 或读取 B 的列方向数据时会出现大 stride。硬件要么做复杂地址生成，要么在计算前额外 pack。分形格式把这一步前移到软件/搬运阶段。

### 2. 降低实时 transpose / pack 压力

GEMM 的 B 矩阵常常需要按和 A 不同的方向喂给阵列。真实昇腾公开文档中，L0A/L0B/L0C 会使用不同的分形/本地格式，以适配 Cube 的左右输入和输出缓冲。

本项目没有完整复刻 `FRACTAL_ZZ`、`FRACTAL_ZN`、`FRACTAL_NZ` 的全部细节，而是先抽象出最重要的一点：Cube 面向固定 16x16 tile，tile 内数据连续，尾块补零。

### 3. 提高 L0 与阵列之间的规则性

规则 tile 的好处是硬件控制简单：

- L0A/L0B 每次写入固定 16 行。
- CubeUnit 每次启动锁存一份完整 16x16 输入 tile。
- SystolicArray 每次执行一个固定形状的 `A_tile * B_tile`。
- L0C 保存一个固定 16x16 partial sum tile，支持 K 方向累加。

这比“任意大小矩阵直接进阵列”更接近真实 AI Core 的编程方式：上层软件负责 tiling 和 padding，Cube 负责高吞吐固定块计算。

### 4. 让尾块处理显式化

真实矩阵维度经常不是 16 的倍数。分形格式要求尾块 padding：

```text
19x21 matrix
  -> 2x2 个 16x16 tiles
  -> packed size = 4 * 16 * 16
  -> 超出原矩阵边界的位置填 0
```

这样 Cube 不需要为每个 PE 单独判断边界。代价是尾块会浪费一部分计算和存储，但控制逻辑更简单。

## 本次代码改动

| 文件 | 变更 |
|---|---|
| `src/main/scala/ascend/AscendParams.scala` | 新增 `FractalTileSize = 16`，`ArraySize` 改为 16 |
| `src/main/scala/ascend/FractalTile.scala` | 新增 pack/unpack、坐标映射和 padding helper |
| `src/test/scala/ascend/FractalTileTest.scala` | 验证 16x16 tile 坐标映射和尾块 padding |
| `src/test/scala/ascend/*` | 相关测试自动使用新的 16x16 tile 尺寸 |

## 优化效果

分形 tile 本身不是“减少 cycle”的技巧，而是把一次 Cube 操作的工作粒度从 8x8 提升到 16x16。单次 tile 的总周期增加了，但完成的 MAC 数从 `8^3 = 512` 增加到 `16^3 = 4096`，是 8 倍工作量。

| 场景 | 旧 8x8 | 新 16x16 | 变化 |
|---|---:|---:|---:|
| 单 tile MAC 数 | 512 | 4096 | 8.00x |
| 单 tile totalCycles | 182 | 347 | 1.91x |
| 单 tile cubeComputeCycles | 15 | 46 | 3.07x |
| total MAC/cycle | 2.81 | 11.80 | 4.20x |
| Cube 有效 MAC/cycle | 34.13 | 89.04 | 2.61x |

流水线测试也体现了同样趋势：

| 场景 | totalCycles | cubeComputeCycles | DMA cycles | overlapCycles |
|---|---:|---:|---:|---:|
| 16x16 单 tile | 347 | 46 | 144 | 94 |
| Pipeline3 3 tile | 855 | 138 | 432 | 182 |
| TripleBuffer 4 tile | 1129 | 184 | 576 | 250 |

结论：

- 16x16 tile 让每次 MATMUL 的绝对周期变长，但每周期完成的有效矩阵工作明显增加。
- DMA 行数也从 8 行变成 16 行，因此 DMA cycles 翻倍；这符合 tile 变大后的搬运成本。
- `overlapCycles` 跟随 Cube 有效计算窗口从每 tile 15 提升到 46，DMA/Cube overlap 更容易观察。

## 限制

当前实现是教学版分形抽象，不是完整昇腾本地格式：

- 没有分别实现 L0A/L0B/L0C 的真实物理排列差异。
- 没有实现 `FRACTAL_ZZ` / `FRACTAL_ZN` / `FRACTAL_NZ` 的所有方向变换。
- 硬件接口仍以二维 `Vec` 表示一个 tile，便于测试和讲解。
- `FractalTile` helper 只在软件侧建模 pack/unpack，后续可扩展成 DMA/MTE 的真实格式转换路径。

参考公开文档：

- [Compute Units - Ascend C Operator Development](https://www.hiascend.com/document/detail/en/canncommercial/800/opdevg/Ascendcopdevg/atlas_ascendc_10_0009.html)
- [存储单元 - Ascend C 算子开发](https://www.hiascend.com/document/detail/zh/CANNCommunityEdition/82RC1alpha002/opdevg/Ascendcopdevg/atlas_ascendc_10_0010.html)
