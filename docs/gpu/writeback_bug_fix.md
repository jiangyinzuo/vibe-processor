# 写回逻辑 Bug 修复

## 问题

EXP 能完成计算，但结果没有写回目标寄存器。集成测试表现为输出全 0。

## 根因

dispatcher 早期用 `Wire` 保存写回元数据：

```scala
val wbRd = Wire(Vec(numCores, UInt(4.W)))
val wbWarpId = Wire(Vec(numCores, UInt(log2Ceil(numWarps).W)))
val wbLaneId = Wire(Vec(numCores, UInt(log2Ceil(warpWidth).W)))
```

这些值在没有新指令分发的周期回到默认值。若执行单元结果延迟 1 cycle 返回，写回阶段可能读到默认 `rd=0`，从而把结果写入 R0 而非目标寄存器。

## 修复

写回元数据改为寄存器，并在分发周期锁存：

```scala
val wbRdReg = RegInit(VecInit(Seq.fill(numCores)(0.U(4.W))))
val wbWarpIdReg = RegInit(VecInit(Seq.fill(numCores)(0.U(log2Ceil(numWarps).W))))
val wbLaneIdReg = RegInit(VecInit(Seq.fill(numCores)(0.U(log2Ceil(warpWidth).W))))
```

所有延迟写回指令都必须走该路径，包括 ADD、MUL、MAD 和 EXP。

## 验证

- `gpu.SFUTest`
- `gpu.SFUIntegrationTest`
- `gpu.SFUDebugTest`
- `gpu.InstructionDispatcherWritebackTest`

## 结论

跨周期有效的控制信息不能用组合 `Wire` 保存。执行单元只要存在返回延迟，就需要把目的寄存器、warp id、lane id 和 valid 信息与结果一起对齐。
