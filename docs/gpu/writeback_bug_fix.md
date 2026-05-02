# 写回逻辑 Bug 修复

## 问题描述

EXP 指令能够正确执行并计算结果，但结果没有写回到正确的寄存器。测试显示所有结果都是 0。

## 根本原因

在 `InstructionDispatcher.scala` 的写回逻辑中，使用了 Wire 类型来保存写回信息：

```scala
val wbRd = Wire(Vec(numCores, UInt(4.W)))
val wbWarpId = Wire(Vec(numCores, UInt(log2Ceil(numWarps).W)))
val wbLaneId = Wire(Vec(numCores, UInt(log2Ceil(warpWidth).W)))

for (i <- 0 until numCores) {
  wbRd(i) := 0.U      // 每个周期都重置为 0
  wbWarpId(i) := 0.U
  wbLaneId(i) := 0.U
}
```

### 时序分析

假设 EXP 指令在周期 N 分发：

- **周期 N**：
  - 指令分发，`wbRd(i)` 被设置为 `rd = 1`
  - `wbRdReg(i) = RegNext(wbRd(i))` 仍然是上一个周期的值（可能是 0）
  - EXP 开始执行

- **周期 N+1**：
  - EXP 完成，`coreDone(i)` 变为 true
  - 如果没有新指令分发，`wbRd(i)` 被重置为 0（Wire 的默认行为）
  - `wbRdReg(i) = RegNext(wbRd(i))` = 0（上一个周期的 wbRd）
  - 写回使用 `wbRdReg(i) = 0`，结果被错误地写入 R0 而不是 R1

### 问题的关键

Wire 类型在每个周期都会被重新计算。如果没有新的赋值，它会保持默认值（0）。这导致：

1. 指令分发和结果写回之间有 1 个周期的延迟
2. 在这个延迟期间，如果没有新指令分发，Wire 会被重置
3. RegNext 会锁存这个被重置的值（0）
4. 写回时使用错误的寄存器地址

## 解决方案

将 Wire 改为 RegInit，使写回信息持久保存：

```scala
val wbRdReg = RegInit(VecInit(Seq.fill(numCores)(0.U(4.W))))
val wbWarpIdReg = RegInit(VecInit(Seq.fill(numCores)(0.U(log2Ceil(numWarps).W))))
val wbLaneIdReg = RegInit(VecInit(Seq.fill(numCores)(0.U(log2Ceil(warpWidth).W))))

// 在分发时更新写回信息
when(op === GpuOpcode.ADD || op === GpuOpcode.MUL || op === GpuOpcode.MAD || op === GpuOpcode.EXP) {
  for (lane <- 0 until warpWidth) {
    val coreId = wbCoreIdx + lane
    if (coreId < numCores) {
      wbRdReg(coreId) := rd      // 直接更新寄存器
      wbWarpIdReg(coreId) := warpId
      wbLaneIdReg(coreId) := lane.U
    }
  }
}
```

### 修复后的时序

- **周期 N**：
  - 指令分发，`wbRdReg(i)` 被设置为 `rd = 1`（寄存器更新）
  - EXP 开始执行

- **周期 N+1**：
  - EXP 完成，`coreDone(i)` 变为 true
  - `wbRdReg(i)` 仍然保持 1（寄存器持久保存）
  - 写回使用 `wbRdReg(i) = 1`，结果正确写入 R1

## 影响范围

这个 bug 影响所有需要延迟写回的指令：
- ADD, MUL, MAD（1 周期延迟）
- EXP（1 周期延迟）

在测试中，ADD/MUL/MAD 可能没有暴露这个问题，因为：
1. 测试程序可能每个周期都有指令分发
2. 或者测试的时序恰好掩盖了这个问题

EXP 指令暴露了这个 bug，因为它是新添加的指令，测试程序更简单，更容易观察到写回失败。

## 经验教训

1. **Wire vs Reg**：Wire 是组合逻辑，每个周期重新计算；Reg 是时序逻辑，保持状态
2. **跨周期状态**：需要跨周期保持的信息必须使用寄存器
3. **时序对齐**：指令分发和结果写回之间的延迟需要仔细处理
4. **测试覆盖**：简单的测试程序更容易暴露时序问题

## 验证

修复后，所有 SFU 测试应该通过：
- SFUTest：单元测试（e^0, e^1, e^-1）
- SFUIntegrationTest：集成测试（EXP + STORE）
- SFUDebugTest：调试测试（单独验证 EXP 和写回）
