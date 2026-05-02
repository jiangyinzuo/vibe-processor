# SFU 完整实现记录

## 概述

在 GPU 中实现了 SFU（Special Function Unit），支持 e^x 函数计算。这是一个从设计、实现到调试的完整过程记录。

## 实现时间线

### 第 1 阶段：设计和基础实现

**目标**: 实现 SFU 模块，支持 e^x 计算

**实现**:
- 创建 `SFU.scala` 模块
- 使用查找表 + 线性插值算法
- Q16.16 定点数格式
- 65 个查找表条目（e^-8 到 e^8，步长 0.25）
- 初始设计：3 级流水线

**文件**:
- `src/main/scala/gpu/SFU.scala`
- `src/main/scala/gpu/GpuParams.scala` (添加 EXP opcode)

### 第 2 阶段：架构集成

**目标**: 将 SFU 集成到 GPU 架构中

**实现**:
- 在 `SMSubPartition.scala` 中实例化 warpWidth 个 SFU
- 在 `InstructionDispatcher.scala` 中添加 EXP 指令分发
- 根据指令类型路由到 CUDA Core 或 SFU
- 添加结果选择逻辑

**文件**:
- `src/main/scala/gpu/SMSubPartition.scala`
- `src/main/scala/gpu/SM.scala`
- `src/main/scala/gpu/InstructionDispatcher.scala`

### 第 3 阶段：测试和调试

**创建测试**:
- `SFUTest.scala`: 单元测试
- `SFUIntegrationTest.scala`: 集成测试
- `SFUDebugTest.scala`: 调试测试

**遇到的问题和解决方案**:

#### 问题 1: 查找表值不准确

**现象**: e^-1 测试失败
- 期望: 24109 (0.368 in Q16.16)
- 实际: 17241

**原因**: 初始查找表中的值计算错误

**解决**: 使用 Python 重新生成正确的查找表

```python
import math
for i in range(65):
    x = -8.0 + i * 0.25
    exp_x = math.exp(x)
    q16_16 = int(exp_x * 65536)
    print(f"0x{q16_16:08X}L  // e^{x:.2f}")
```

#### 问题 2: 集成测试结果全为 0

**现象**: 
- 单元测试通过 ✅
- 集成测试失败 ❌ (结果全为 0)

**原因 1**: 写回逻辑中缺少 EXP 指令

在 `InstructionDispatcher.scala` 的写回逻辑中，只处理了 ADD/MUL/MAD，没有处理 EXP。

**解决 1**: 在第 266 行添加 EXP 到写回逻辑

```scala
when(op === GpuOpcode.ADD || op === GpuOpcode.MUL || 
     op === GpuOpcode.MAD || op === GpuOpcode.EXP) {
  // 记录 rd
}
```

**原因 2**: SFU 延迟与写回逻辑不匹配

3 周期延迟导致 rd 信息过期。

**解决 2**: 将 SFU 改为 1 周期延迟

从 3 级流水线简化为 1 周期延迟，与 CUDA Core 保持一致。

```scala
// 移除 stage2 和 stage3 寄存器
val result_reg = RegNext(Mux(io.valid, exp_result, 0.S), 0.S)
val done_reg = RegNext(io.valid, false.B)
```

#### 问题 3: 时序不匹配

**现象**: 
- 简化 SFU 为 1 周期延迟后，测试仍然失败
- 结果仍然全为 0

**根本原因**: `isExpInstr` 信号时序不匹配

在 `SMSubPartition.scala` 中，`isExpInstr` 用于选择 SFU 或 CUDA Core 的结果，但这个信号是在**当前周期**计算的，而 SFU 有 1 周期延迟。

**时序分析**:

```
周期 0: 分发 EXP 指令
        isExpInstr = true (当前指令是 EXP)
        SFU.valid = true
        
周期 1: SFU 完成计算
        isExpInstr = false (当前指令已经是下一条了)
        sfuDone = true
        Mux 选择 CUDA Core 结果 (因为 isExpInstr = false)
        结果没有写回！
```

**解决方案**: 延迟 `isExpInstr` 信号 1 周期

```scala
val isExpInstrVec = Wire(Vec(numCores, Bool()))
val isExpInstrRegVec = RegNext(isExpInstrVec)  // 延迟 1 周期

for (i <- 0 until numCores) {
  val isExpInstr = dispatcher.io.coreOp(i) === GpuOpcode.EXP
  isExpInstrVec(i) := isExpInstr
  
  // 输入使用当前周期的 isExpInstr
  sfus(i).io.valid := dispatcher.io.coreValid(i) && isExpInstr
  
  // 结果选择使用延迟后的 isExpInstrRegVec
  dispatcher.io.coreDone(i) := Mux(isExpInstrRegVec(i), sfuDone, cudaCores(i).io.done)
  dispatcher.io.coreRd(i) := Mux(isExpInstrRegVec(i), sfuResult, cudaCores(i).io.rd)
}
```

**修复后的时序**:

```
周期 0: 分发 EXP 指令
        isExpInstr = true
        isExpInstrRegVec = false (上一周期的值)
        SFU.valid = true
        
周期 1: SFU 完成计算
        isExpInstr = false (下一条指令)
        isExpInstrRegVec = true (延迟后的 EXP 标记)
        sfuDone = true
        Mux 选择 SFU 结果 (因为 isExpInstrRegVec = true)
        结果正确写回！
```

#### 问题 4: 查找表索引宽度警告

**现象**: 编译警告

```
[W004] Dynamic index with width 18 is too wide for Vec of size 65 
(expected index width 7).
```

**原因**: `index_raw` 是 18 位宽（32 位右移 14 位），但查找表只需要 7 位索引（log2(65) = 7）

**解决**: 限制索引宽度为 7 位

```scala
val index = Mux(index_raw > 64.U, 64.U, index_raw(6, 0))  // 限制为 7 位
```

## 最终实现

### 架构

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

### 关键参数

- **数据格式**: Q16.16 定点数
- **输入范围**: [-8, 8]
- **输出范围**: [e^-8, e^8] ≈ [0.000335, 2981]
- **查找表大小**: 65 个条目 × 32 bits = 260 bytes
- **延迟**: 1 周期
- **SFU 数量**: 当前 8 个（2 个 sub-partition × warpWidth=4）

### 性能

- **延迟**: 1 周期（与 CUDA Core 一致）
- **吞吐量**: 每周期 1 个 Warp（所有 lane 并行）
- **精度**: 误差 < 1%

## 测试结果

### 单元测试 (SFUTest)

- ✅ e^0 = 1.0 (65536)
- ✅ e^1 ≈ 2.718 (178145)
- ✅ e^-1 ≈ 0.368 (24109)
- ✅ 无效输入输出 0

### 集成测试 (SFUIntegrationTest)

- ✅ 完整的 LD -> EXP -> ST 流程
- ✅ 结果正确写回到内存

### 调试测试 (SFUDebugTest)

- ✅ 简单 EXP 指令执行
- ✅ EXP + STORE 流程

## 关键设计决策

### 1. 为什么选择 1 周期延迟？

**优点**:
- 与 CUDA Core 延迟一致，简化写回逻辑
- 不需要额外的写回队列
- 降低实现复杂度

**缺点**:
- 牺牲了一些流水线效率
- 无法支持多个 Warp 并发

**权衡**: 对于玩具 GPU，简单性比性能更重要

### 2. 为什么每个 lane 一个 SFU？

**优点**:
- 最大并行度：一个 Warp 的所有 lane 可以同时计算
- 简化调度：不需要 lane 之间的仲裁

**缺点**:
- 增加了硬件资源消耗（当前 8 个 SFU）

**权衡**: 真实 GPU 通常每个 SM 只有 4 个 SFU，需要多周期完成一个 Warp

### 3. 为什么使用线性插值？

**优点**:
- 硬件简单：只需要一次乘法和一次加法
- 延迟低：可以在 1 周期内完成
- 精度足够：对于大多数应用，1% 误差可接受

**缺点**:
- 精度不如二次插值

**权衡**: 真实 GPU 使用二次插值，但需要更多硬件资源

## 经验教训

### 1. 时序是硬件设计的核心

组合逻辑和时序逻辑的混用容易出错。所有控制信号必须与数据路径延迟匹配。

### 2. 测试要全面

- 单元测试：验证模块功能
- 集成测试：验证完整流程
- 端到端测试：验证结果写回

### 3. 调试要系统

- 从简单到复杂
- 逐步定位问题
- 使用波形图辅助调试

### 4. 文档很重要

- 记录设计决策
- 记录遇到的问题和解决方案
- 方便后续维护和扩展

## 代码统计

### 新增文件

- `src/main/scala/gpu/SFU.scala`: 195 行
- `src/test/scala/gpu/SFUTest.scala`: 63 行
- `src/test/scala/gpu/SFUIntegrationTest.scala`: 95 行
- `src/test/scala/gpu/SFUDebugTest.scala`: 116 行

### 修改文件

- `src/main/scala/gpu/GpuParams.scala`: +1 行
- `src/main/scala/gpu/InstructionDispatcher.scala`: +2 行
- `src/main/scala/gpu/SMSubPartition.scala`: SFU 与 CudaCore lane group
- `src/main/scala/gpu/SM.scala`: sub-partition 顶层连接

### 文档

- `docs/gpu/sfu.md`: 200+ 行
- `docs/gpu/sfu_implementation_summary.md`: 300+ 行
- `docs/gpu/sfu_timing_fix.md`: 250+ 行
- `docs/gpu/sfu_complete_implementation.md`: 本文档

**总计**: ~1200 行代码和文档

## 未来扩展

### 1. 支持更多函数

- LOG: log(x)
- SIN/COS: 三角函数
- SQRT: 平方根
- RSQRT: 倒数平方根

### 2. 提高精度

- 使用二次插值
- 增加查找表密度
- 支持更高精度的定点数（Q24.8 或 Q20.12）

### 3. 优化性能

- 增加流水线级数（2-3 级）
- 支持多个 Warp 并发
- 减少 SFU 数量（每个 SM 4 个，需要多周期完成）

### 4. 硬件优化

- 使用 ROM 代替寄存器存储查找表
- 优化乘法器和加法器
- 减少资源消耗

## 相关文档

- [SFU 技术文档](sfu.md)
- [SFU 实现总结](sfu_implementation_summary.md)
- [SFU 时序修复](sfu_timing_fix.md)
- [共享架构总结](shared_architecture_summary.md)
- [内存数据缓冲修复](memory_data_buffering.md)

## 总结

成功在 GPU 中实现了 SFU，支持 e^x 函数计算。实现过程中遇到了多个问题，包括查找表值错误、写回逻辑缺失、延迟不匹配、时序不匹配等，通过系统的调试和修复，最终所有测试通过。

这个实现为后续扩展更多超越函数奠定了基础，也积累了宝贵的硬件设计和调试经验。
