# SFU 实现记录

本文是 SFU 集成过程的归档记录。稳定接口和使用方法见 [sfu.md](sfu.md)。

## 实现范围

- 新增 `src/main/scala/gpu/SFU.scala`。
- 在 `GpuParams` 中加入 `EXP` opcode。
- 在 `InstructionDispatcher` 中支持 EXP 分发和写回。
- 在 `SMSubPartition` 中为每个 lane 接入 SFU。
- 增加 `SFUTest`、`SFUDebugTest`、`SFUIntegrationTest`。

## SFU 规格

| 项目 | 当前实现 |
|---|---|
| 函数 | `e^x` |
| 输入格式 | Q16.16 |
| 输入范围 | `[-8, 8]` |
| 实现方法 | 65 项 LUT + 线性插值 |
| 延迟 | 1 cycle |
| 精度 | 目标误差小于 1% |

## 集成路径

```text
Warp Scheduler
  -> InstructionDispatcher
  -> CudaCore / SFU
  -> SharedRegisterFile
```

EXP 与 ADD/MUL/MAD 一样走延迟写回路径。关键约束是控制信号必须与执行单元输出同周期到达 dispatcher。

## 修复记录

| 问题 | 原因 | 修复 |
|---|---|---|
| `e^-1` 结果错误 | LUT 值生成错误 | 重新生成 Q16.16 查找表 |
| EXP 结果未写回 | dispatcher 未记录 EXP 的 `rd` | 将 EXP 纳入写回记录 |
| 集成测试输出 0 | SFU 延迟与结果选择信号错位 | `isExpInstr` 延迟 1 cycle |
| LUT 索引告警 | 动态索引位宽大于 Vec 索引需求 | clamp 后取 7 bit 索引 |

## 验证

```bash
sbt "testOnly gpu.SFUTest gpu.SFUDebugTest gpu.SFUIntegrationTest"
```

测试覆盖：

- `e^0 = 1.0`
- `e^1 ~= 2.718`
- `e^-1 ~= 0.368`
- 无效输入输出 0
- `LD -> EXP -> ST` 集成路径

## 限制

- 仅支持 `exp`。
- 每 lane 一个 SFU，资源开销高于真实 GPU 的分区共享方式。
- 1 cycle 延迟是教学简化，不代表真实 SFU 时序。
