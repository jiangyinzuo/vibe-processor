# SFU 时序修复文档

## 问题描述

SFU 集成测试失败，所有结果都是 0，而不是期望的 e^x 值。

### 测试结果

```
SFUTest: ✅ 4/4 通过
- e^0 = 1.0 ✓
- e^1 ≈ 2.718 ✓
- e^-1 ≈ 0.368 ✓
- 无效输入输出 0 ✓

SFUDebugTest: ❌ 1/2 失败
- 简单 EXP 测试 ✓
- EXP + STORE 测试 ✗ (结果全为 0)

SFUIntegrationTest: ❌ 0/1 失败
- 完整流程测试 ✗ (结果全为 0)
```

## 根本原因

**时序不匹配问题**：在 SM_Shared.scala 中，`isExpInstr` 信号用于选择 SFU 或 CUDA Core 的结果，但这个信号是在**当前周期**根据分发的指令计算的。

### 问题分析

```scala
// 错误的实现
for (i <- 0 until numCores) {
  val isExpInstr = dispatcher.io.coreOp(i) === GpuOpcode.EXP  // 当前周期的指令
  
  // SFU 有 1 周期延迟
  sfus(i).io.valid := dispatcher.io.coreValid(i) && isExpInstr
  
  // 结果选择使用当前周期的 isExpInstr
  dispatcher.io.coreDone(i) := Mux(isExpInstr, sfuDone, cudaCores(i).io.done)
  dispatcher.io.coreRd(i) := Mux(isExpInstr, sfuResult, cudaCores(i).io.rd)
}
```

### 时序图

```
周期 0: 分发 EXP 指令
        isExpInstr = true
        SFU.valid = true
        
周期 1: SFU 完成计算
        isExpInstr = false (已经是下一条指令了！)
        sfuDone = true
        但是 Mux 选择了 CUDA Core 的结果（因为 isExpInstr = false）
        结果没有写回！
```

### 为什么会这样？

1. **SFU 延迟**: SFU 有 1 周期延迟，输入在周期 N，输出在周期 N+1
2. **指令流**: 每个周期都可能分发新指令，所以 `dispatcher.io.coreOp(i)` 每周期都在变化
3. **选择信号**: `isExpInstr` 是组合逻辑，立即反映当前周期的指令
4. **结果选择**: 当 SFU 在周期 N+1 完成时，`isExpInstr` 已经是周期 N+1 的指令了

## 解决方案

**延迟 `isExpInstr` 信号 1 周期**，使其与 SFU 的延迟匹配。

### 修复代码

```scala
// 正确的实现
val isExpInstrVec = Wire(Vec(numCores, Bool()))
val isExpInstrRegVec = RegNext(isExpInstrVec)  // 延迟 1 周期

for (i <- 0 until numCores) {
  val isExpInstr = dispatcher.io.coreOp(i) === GpuOpcode.EXP
  isExpInstrVec(i) := isExpInstr  // 记录当前周期的指令类型
  
  // SFU 输入使用当前周期的 isExpInstr
  sfus(i).io.valid := dispatcher.io.coreValid(i) && isExpInstr
  
  // 结果选择使用延迟后的 isExpInstrRegVec
  dispatcher.io.coreDone(i) := Mux(isExpInstrRegVec(i), sfuDone, cudaCores(i).io.done)
  dispatcher.io.coreRd(i) := Mux(isExpInstrRegVec(i), sfuResult, cudaCores(i).io.rd)
}
```

### 修复后的时序图

```
周期 0: 分发 EXP 指令
        isExpInstr = true
        isExpInstrRegVec = false (上一周期的值)
        SFU.valid = true
        
周期 1: SFU 完成计算
        isExpInstr = false (下一条指令)
        isExpInstrRegVec = true (延迟后的 EXP 标记)
        sfuDone = true
        Mux 选择 SFU 结果（因为 isExpInstrRegVec = true）
        结果正确写回！
```

## 关键要点

### 1. 流水线延迟匹配

当一个模块有延迟时，所有相关的控制信号都必须延迟相同的周期数。

```
输入信号 ──┐
           ├─> 模块 (N 周期延迟) ──> 输出
控制信号 ──┘
           │
           └─> RegNext^N ──> 延迟后的控制信号
```

### 2. 为什么 CUDA Core 不需要延迟？

CUDA Core 也有 1 周期延迟，但它的 `done` 信号已经自带延迟：

```scala
// CUDA Core 内部
val doneReg = RegNext(io.valid)
io.done := doneReg
```

所以 CUDA Core 的 `done` 信号在周期 N+1 才会变为 true，与当前周期的 `isExpInstr` 匹配。

但 SFU 的 `done` 信号也是延迟的，所以需要延迟 `isExpInstr` 来匹配。

### 3. 为什么单元测试通过？

单元测试直接测试 SFU 模块，不涉及指令分发和结果选择逻辑，所以没有暴露这个问题。

### 4. 为什么简单 EXP 测试通过？

简单 EXP 测试只执行 EXP 指令然后 HALT，没有后续指令。在 HALT 时，`isExpInstr` 仍然是 false（HALT 不是 EXP），但此时已经没有新的计算了。

但这个测试没有验证结果是否写回到寄存器，只是检查了 HALT 状态。

## 类似问题的预防

### 设计原则

1. **明确延迟**: 每个模块的接口文档应该明确说明延迟周期数
2. **控制信号延迟**: 所有控制信号必须与数据路径延迟匹配
3. **端到端测试**: 单元测试 + 集成测试，确保时序正确

### 检查清单

- [ ] 模块延迟是否明确？
- [ ] 控制信号是否延迟匹配？
- [ ] 是否有端到端测试验证写回？
- [ ] 是否测试了连续指令的场景？

## 修复文件

- `src/main/scala/gpu/SM_Shared.scala`: 添加 `isExpInstrRegVec` 延迟信号

## 测试验证

修复后需要验证：
- ✅ SFU 单元测试通过
- ✅ SFU 集成测试通过
- ✅ 结果正确写回到寄存器
- ✅ 结果正确写回到内存

## 经验教训

1. **时序是硬件设计的核心**: 组合逻辑和时序逻辑的混用容易出错
2. **延迟必须匹配**: 数据路径和控制路径的延迟必须一致
3. **测试要全面**: 单元测试不够，需要集成测试验证完整流程
4. **调试要系统**: 从简单到复杂，逐步定位问题

## 相关文档

- [SFU 实现文档](sfu.md)
- [SFU 实现总结](sfu_implementation_summary.md)
- [内存数据缓冲修复](memory_data_buffering.md)
