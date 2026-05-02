# GPU SFU 实现总结

## 实现内容

成功在 GPU 中实现了 SFU（Special Function Unit），支持 e^x 函数计算。

## 核心文件

### 1. 新增文件

- **src/main/scala/gpu/SFU.scala** (195 行)
  - SFU 模块实现
  - 查找表 + 线性插值算法
  - 1 周期延迟（与 CUDA Core 一致）

- **src/test/scala/gpu/SFUTest.scala** (63 行)
  - SFU 单元测试
  - 测试 e^0, e^1, e^-1 的计算精度

- **src/test/scala/gpu/SFUIntegrationTest.scala** (95 行)
  - GPU 集成测试
  - 测试完整的 LD -> EXP -> ST 流程

- **src/test/scala/gpu/SFUDebugTest.scala** (116 行)
  - 调试测试
  - 简化的测试场景

- **docs/gpu/sfu.md** (200+ 行)
  - SFU 技术文档
  - 设计说明、使用示例、性能分析

### 2. 修改文件

- **src/main/scala/gpu/GpuParams.scala**
  - 添加 EXP 操作码（0x8）

- **src/main/scala/gpu/InstructionDispatcher.scala**
  - 支持 EXP 指令分发
  - 添加 EXP 到写回逻辑

- **src/main/scala/gpu/SMSubPartition.scala**
  - 每个分区实例化 warpWidth 个 SFU
  - 根据指令类型路由到 CUDA Core 或 SFU
  - 结果选择逻辑

## 技术细节

### 数据格式

- **Q16.16 定点数**: 16位整数 + 16位小数
- **输入范围**: [-8, 8]
- **输出范围**: [e^-8, e^8] ≈ [0.000335, 2981]

### 实现方法

1. **查找表（LUT）**
   - 65 个关键点：e^-8 到 e^8，步长 0.25
   - 每个值用 Q16.16 格式存储
   - 表大小：65 × 32 bits = 260 bytes

2. **线性插值**
   - 公式：`result = v0 + (v1 - v0) × frac / 4`
   - 精度：误差 < 1%

3. **延迟**
   - 1 周期（与 CUDA Core 一致）
   - 简化了写回时序问题

### 架构集成

```
Warp Scheduler
     ↓
Instruction Dispatcher
     ↓
  ┌──────┴──────┐
  ↓             ↓
CUDA Core      SFU
(ADD/MUL/MAD)  (EXP)
  ↓             ↓
  └──────┬──────┘
         ↓
  Register File
```

## 测试结果

### 单元测试

- ✅ e^0 = 1.0 (65536)
- ✅ e^1 ≈ 2.718 (178145)
- ✅ e^-1 ≈ 0.368 (24109)
- ✅ 无效输入时输出 0

### 集成测试

- ✅ 简单 EXP 指令执行
- ✅ EXP + STORE 流程
- ✅ 完整的 LD -> EXP -> ST 流程

## 性能特点

### 延迟

- **SFU 延迟**: 1 周期
- **总延迟**: ~3-4 周期（包括发射和写回）

### 吞吐量

- **理论吞吐量**: 每周期 1 个 Warp（所有 lane 并行）
- **实际吞吐量**: 受限于 Warp 调度

### 资源消耗

- **SFU 数量**: 当前 8 个（2 个 sub-partition × warpWidth=4）
- **查找表**: 260 bytes per SFU
- **总存储**: ~1-2 KB

## 与真实 GPU 对比

| 特性 | NVIDIA GPU | 昇腾 NPU | 玩具 GPU |
|------|-----------|---------|---------|
| SFU 数量 | 多个/SM，按架构分区 | Vector Unit | 8 per SM |
| 延迟 | 16-32 周期 | ~10 周期 | 1 周期 |
| 支持函数 | exp, log, sin, cos, sqrt, rsqrt | exp, log, sin, cos | exp |
| 实现方法 | 查找表 + 二次插值 | 查找表 + 线性插值 | 查找表 + 线性插值 |

## 未来扩展

### 1. 支持更多函数

- LOG: log(x)
- SIN/COS: 三角函数
- SQRT: 平方根
- RSQRT: 倒数平方根

### 2. 提高精度

- 使用二次插值
- 增加查找表密度

### 3. 优化性能

- 增加流水线级数（2-3 级）
- 支持多个 Warp 并发

## 关键设计决策

### 1. 为什么选择 1 周期延迟？

- **简化写回逻辑**: 与 CUDA Core 延迟一致，避免复杂的时序处理
- **降低实现复杂度**: 不需要额外的写回队列
- **权衡**: 牺牲了一些流水线效率，但提高了可维护性

### 2. 为什么每个 lane 一个 SFU？

- **最大并行度**: 一个 Warp 的所有 lane 可以同时计算
- **简化调度**: 不需要 lane 之间的仲裁
- **权衡**: 增加了硬件资源消耗

### 3. 为什么使用线性插值而非二次插值？

- **硬件简单**: 只需要一次乘法和一次加法
- **延迟低**: 可以在 1 周期内完成
- **精度足够**: 对于大多数应用，1% 误差可接受

## 遇到的问题和解决方案

### 问题 1: 查找表值不准确

**现象**: e^-1 测试失败，期望 24109，得到 17241

**原因**: 初始查找表中的值计算错误

**解决**: 使用 Python 重新生成正确的查找表

### 问题 2: 集成测试结果全为 0

**现象**: EXP 指令执行，但结果没有写回到内存

**原因**: 
1. 写回逻辑中缺少 EXP 指令的 rd 记录
2. 早期 SFU 3 周期延迟与 CUDA Core 1 周期延迟不匹配

**解决**:
1. 在 InstructionDispatcher 的写回逻辑中添加 EXP
2. 将 SFU 改为 1 周期延迟

### 问题 3: 测试程序使用 R15 寄存器

**现象**: R15 未初始化，导致地址计算错误

**解决**: 改用 R0 寄存器（初始值为 0）

## 代码统计

- **新增代码**: ~670 行
  - SFU.scala: 195 行
  - 测试代码: 274 行
  - 文档: 200+ 行

- **修改代码**: ~50 行
  - GpuParams.scala: 1 行
  - InstructionDispatcher.scala: 2 行
  - SM.scala: 27 行

## 总结

成功在 GPU 中实现了 SFU，支持 e^x 函数计算。实现采用了查找表 + 线性插值的方法，延迟为 1 周期，与 CUDA Core 保持一致。所有单元测试和集成测试通过，验证了功能的正确性。

这个实现为后续扩展更多超越函数（log, sin, cos, sqrt 等）奠定了基础。
