# SFU 状态记录

本文保留 SFU 集成工作的最终状态，替代早期“正在测试”的临时记录。

## 当前状态

- `EXP` 指令已接入 GPU ISA，opcode 为 `0x8`。
- `SFU.scala` 使用 Q16.16 定点数、65 项查找表和线性插值计算 `e^x`。
- SFU 延迟为 1 周期，与 CudaCore 写回路径对齐。
- `SMSubPartition` 使用延迟后的 `isExpInstr` 选择 SFU 结果，避免控制信号与数据路径错位。
- 查找表索引宽度已限制为 7 bit，避免动态索引宽度告警。

## 已修复问题

| 问题 | 修复 |
|---|---|
| 查找表值错误 | 重新生成 `e^-8` 到 `e^8` 的 Q16.16 LUT |
| EXP 未进入写回逻辑 | 在 dispatcher 写回记录中纳入 `GpuOpcode.EXP` |
| SFU 延迟与写回不匹配 | 将 SFU 简化为 1 周期延迟 |
| `isExpInstr` 与 SFU 输出错位 | 对控制信号打一拍，与 SFU 输出对齐 |
| LUT 动态索引过宽 | clamp 后取 `index_raw(6, 0)` |

## 验证入口

```bash
sbt "testOnly gpu.SFUTest gpu.SFUDebugTest gpu.SFUIntegrationTest"
```

相关文档：

- [SFU 技术文档](sfu.md)
- [SFU 时序修复](sfu_timing_fix.md)
- [写回逻辑 Bug 修复](writeback_bug_fix.md)
