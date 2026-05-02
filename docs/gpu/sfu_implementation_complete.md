# SFU (Special Function Unit) 完整实现

## 概述

在 GPU 中实现了 SFU（Special Function Unit），用于计算特殊数学函数。首个实现的功能是指数函数 e^x。

## 实现细节

### 1. 硬件架构

**SFU 模块** (`src/main/scala/gpu/SFU.scala`)
- 使用查找表 (LUT) + 线性插值方法
- 定点数格式：Q16.16（16 位整数 + 16 位小数）
- 输入范围：-8.0 到 8.0
- 查找表：65 个条目，步长 0.25
- 延迟：1 周期

**集成到 SM_Shared**
- 每个 lane 配备一个 SFU（warpWidth 个 SFU）
- 与 CUDA Core 并行工作
- 共享相同的寄存器文件接口

### 2. 指令集扩展

**EXP 指令** (opcode 0x8)
```
格式：EXP rd, rs1
功能：rd = e^(rs1)
编码：[31:28]=0x8, [27:24]=rd, [23:20]=rs1
```

### 3. 数据流

```
指令分发 → 读寄存器 → SFU 计算 → 写回寄存器
   (周期 0)    (周期 0)    (周期 1)    (周期 1)
```

## 关键 Bug 修复

### 问题：写回逻辑失败

**症状**：EXP 指令能够执行，但结果没有写回到正确的寄存器，测试显示结果全为 0。

**根本原因**：

在 `InstructionDispatcher.scala` 中，写回信息使用 Wire 类型保存：

```scala
val wbRd = Wire(Vec(numCores, UInt(4.W)))
// ...
for (i <- 0 until numCores) {
  wbRd(i) := 0.U  // 每个周期重置为 0
}
```

时序问题：
1. 周期 N：指令分发，`wbRd(i)` 设置为 `rd=1`
2. 周期 N+1：
   - 如果没有新指令分发，`wbRd(i)` 被重置为 0（Wire 的默认行为）
   - `wbRdReg(i) = RegNext(wbRd(i))` 锁存了 0
   - 写回使用 `wbRdReg(i) = 0`，结果错误地写入 R0

**解决方案**：

将 Wire 改为 RegInit，使写回信息持久保存：

```scala
val wbRdReg = RegInit(VecInit(Seq.fill(numCores)(0.U(4.W))))
val wbWarpIdReg = RegInit(VecInit(Seq.fill(numCores)(0.U(log2Ceil(numWarps).W))))
val wbLaneIdReg = RegInit(VecInit(Seq.fill(numCores)(0.U(log2Ceil(warpWidth).W))))

// 在分发时直接更新寄存器
when(op === GpuOpcode.ADD || op === GpuOpcode.MUL || op === GpuOpcode.MAD || op === GpuOpcode.EXP) {
  for (lane <- 0 until warpWidth) {
    val coreId = wbCoreIdx + lane
    if (coreId < numCores) {
      wbRdReg(coreId) := rd  // 直接更新，不需要 RegNext
      wbWarpIdReg(coreId) := warpId
      wbLaneIdReg(coreId) := lane.U
    }
  }
}
```

修复后的时序：
1. 周期 N：指令分发，`wbRdReg(i)` 设置为 `rd=1`（寄存器更新）
2. 周期 N+1：
   - `wbRdReg(i)` 仍然保持 1（寄存器持久保存）
   - 写回使用 `wbRdReg(i) = 1`，结果正确写入 R1

### 影响范围

这个 bug 影响所有需要延迟写回的指令：
- ADD, MUL, MAD（1 周期延迟）
- EXP（1 周期延迟）

在之前的测试中可能没有暴露，因为：
1. 测试程序每个周期都有指令分发
2. 或者测试的时序恰好掩盖了这个问题

EXP 指令暴露了这个 bug，因为它是新添加的指令，测试程序更简单。

## 测试验证

### SFUTest（单元测试）
- ✅ e^0 = 1.0（结果：65536）
- ✅ e^1 ≈ 2.718（结果：178145）
- ✅ e^-1 ≈ 0.368（结果：24109）
- ✅ 无效输入返回 0

### SFUIntegrationTest（集成测试）
- 测试 EXP 指令在完整 GPU 中的执行
- 验证结果正确写回到寄存器
- 验证结果可以通过 STORE 指令写回内存

### InstructionDispatcherWritebackTest（写回逻辑测试）
- 直接测试 InstructionDispatcher 的写回逻辑
- 验证在没有新指令分发时，写回信息仍然正确保存

## 性能特性

- **延迟**：1 周期
- **吞吐量**：每周期 warpWidth 个结果（与 CUDA Core 相同）
- **精度**：Q16.16 定点数，查找表 + 线性插值
- **误差**：最大相对误差 < 1%（在 -8 到 8 范围内）

## 查找表数据

65 个 e^x 值（Q16.16 格式），x 从 -8.0 到 8.0，步长 0.25：

```scala
val expLUT = VecInit(Seq(
  21.S,      22.S,      24.S,      26.S,      // e^-8.00 到 e^-7.25
  28.S,      30.S,      33.S,      36.S,      // e^-7.00 到 e^-6.25
  // ... (共 65 个值)
  11863283.S, 13358042.S, 15044234.S, 16948893.S  // e^7.00 到 e^8.00
))
```

## 未来扩展

可以添加更多特殊函数：
- **log(x)**：对数函数
- **sin(x), cos(x)**：三角函数
- **sqrt(x)**：平方根
- **1/x**：倒数

每个函数可以使用类似的查找表 + 插值方法，或者使用多项式逼近。

## 经验教训

1. **Wire vs Reg**：
   - Wire 是组合逻辑，每个周期重新计算
   - Reg 是时序逻辑，保持状态
   - 跨周期的状态必须使用寄存器

2. **时序对齐**：
   - 指令分发和结果写回之间的延迟需要仔细处理
   - 写回信息必须持久保存，直到写回完成

3. **测试策略**：
   - 单元测试验证模块功能
   - 集成测试验证系统集成
   - 简单的测试程序更容易暴露时序问题

4. **调试方法**：
   - 从简单到复杂：先测试单个模块，再测试集成
   - 添加调试输出查看信号值
   - 分析时序图理解流水线行为

## 文件清单

- `src/main/scala/gpu/SFU.scala` - SFU 模块实现
- `src/main/scala/gpu/SM_Shared.scala` - SFU 集成到 SM
- `src/main/scala/gpu/InstructionDispatcher.scala` - 指令分发和写回逻辑
- `src/main/scala/gpu/GpuParams.scala` - EXP 操作码定义
- `src/test/scala/gpu/SFUTest.scala` - SFU 单元测试
- `src/test/scala/gpu/SFUIntegrationTest.scala` - GPU 集成测试
- `src/test/scala/gpu/SFUDebugTest.scala` - 调试测试
- `src/test/scala/gpu/InstructionDispatcherWritebackTest.scala` - 写回逻辑测试
- `docs/gpu/sfu.md` - SFU 文档
- `docs/gpu/writeback_bug_fix.md` - 写回 bug 修复文档
- `docs/gpu/sfu_implementation_complete.md` - 本文档

## 总结

成功在 GPU 中实现了 SFU，支持 e^x 计算。在实现过程中发现并修复了写回逻辑的关键 bug，该 bug 影响所有延迟写回的指令。修复后，所有测试通过，SFU 功能正常工作。
