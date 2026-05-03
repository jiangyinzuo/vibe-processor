# SFU 时序修复

## 问题

SFU 单元测试通过，但集成路径曾出现 `EXP + STORE` 结果为 0。根因是 SFU 结果返回时，结果选择控制信号已经切换到下一条指令。

## 错误时序

```text
cycle N:
  dispatcher 发射 EXP
  isExpInstr = true
  SFU.valid = true

cycle N+1:
  SFU.done = true
  isExpInstr 已反映下一条指令
  dispatcher 选择了 CudaCore 结果
```

`isExpInstr` 是组合信号，不能直接用于选择下一周期返回的 SFU 结果。

## 修复

对指令类型打一拍，使控制路径与 SFU 的 1 cycle 数据路径对齐：

```scala
val isExpInstrVec = Wire(Vec(numCores, Bool()))
val isExpInstrRegVec = RegNext(isExpInstrVec)

sfus(i).io.valid := dispatcher.io.coreValid(i) && isExpInstrVec(i)
dispatcher.io.coreDone(i) := Mux(isExpInstrRegVec(i), sfuDone, cudaCores(i).io.done)
dispatcher.io.coreRd(i) := Mux(isExpInstrRegVec(i), sfuResult, cudaCores(i).io.rd)
```

## 规则

- 执行单元有 N cycle 延迟时，结果选择、写回目的寄存器和有效位都必须保持 N cycle。
- 单元测试只能证明执行单元本身正确，不能证明 dispatcher、写回和 STORE 路径正确。
- 集成测试应覆盖 `LD -> execute -> ST` 的端到端路径。

## 验证

```bash
sbt "testOnly gpu.SFUTest gpu.SFUDebugTest gpu.SFUIntegrationTest"
```

相关文件：

- `src/main/scala/gpu/SMSubPartition.scala`
- `src/main/scala/gpu/SFU.scala`
- `src/main/scala/gpu/InstructionDispatcher.scala`
