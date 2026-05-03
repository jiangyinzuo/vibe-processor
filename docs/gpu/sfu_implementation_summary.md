# GPU SFU 实现摘要

SFU 为 toy GPU 增加 `EXP` 指令，用于计算 Q16.16 格式的 `e^x`。详细接口见 [sfu.md](sfu.md)，调试过程见 [sfu_complete_implementation.md](sfu_complete_implementation.md)。

## 文件

| 文件 | 作用 |
|---|---|
| `src/main/scala/gpu/SFU.scala` | LUT + 线性插值实现 |
| `src/main/scala/gpu/GpuParams.scala` | 定义 `GpuOpcode.EXP = 0x8` |
| `src/main/scala/gpu/InstructionDispatcher.scala` | EXP 解码、发射和写回 |
| `src/main/scala/gpu/SMSubPartition.scala` | 接入 lane 级 SFU |
| `src/test/scala/gpu/SFUTest.scala` | SFU 单元测试 |
| `src/test/scala/gpu/SFUIntegrationTest.scala` | `LD -> EXP -> ST` 集成测试 |

## 规格

| 项目 | 数值 |
|---|---|
| 数据格式 | Q16.16 |
| 输入范围 | `[-8, 8]` |
| LUT | 65 项，步长 0.25 |
| 插值 | 线性插值 |
| 延迟 | 1 cycle |
| 当前函数 | `exp` |

## 与真实 GPU 的差距

| 维度 | toy GPU | 真实 GPU |
|---|---|---|
| 函数集合 | exp | exp/log/sin/cos/sqrt 等 |
| 延迟 | 1 cycle | 多周期 |
| 资源组织 | 当前每 lane 一个 SFU | 通常按 SM 分区共享 |
| 近似方法 | LUT + 线性插值 | 更复杂近似和流水 |

## 后续方向

- 支持更多特殊函数。
- 将 SFU 改为分区共享资源，减少每 lane 独占开销。
- 增加多周期 SFU 和写回队列，避免以 1 cycle 简化真实时序。
