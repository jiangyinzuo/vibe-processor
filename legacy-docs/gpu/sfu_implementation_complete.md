# SFU 集成与写回修复

本文聚焦 SFU 接入后暴露的写回问题。SFU 本身的规格见 [sfu.md](sfu.md)。

## 现象

EXP 指令能够触发 SFU 计算，但集成测试中结果没有写回目标寄存器，最终存入内存的值为 0。

## 原因

早期 dispatcher 用 `Wire` 保存写回目的寄存器：

```scala
val wbRd = Wire(Vec(numCores, UInt(4.W)))
```

`Wire` 每周期重新计算。若执行单元结果在下一周期返回，而该周期没有新的分发动作，默认值会覆盖上一周期的 `rd`，导致写回使用错误目的寄存器。

## 修复

将写回元数据改为寄存器，在分发时锁存：

```scala
val wbRdReg = RegInit(VecInit(Seq.fill(numCores)(0.U(4.W))))
val wbWarpIdReg = RegInit(VecInit(Seq.fill(numCores)(0.U(log2Ceil(numWarps).W))))
val wbLaneIdReg = RegInit(VecInit(Seq.fill(numCores)(0.U(log2Ceil(warpWidth).W))))
```

EXP、ADD、MUL、MAD 都走同一类延迟写回路径，因此该修复不只影响 SFU。

## 验证

```bash
sbt "testOnly gpu.SFUTest gpu.SFUIntegrationTest gpu.SFUDebugTest"
```

验证点：

- SFU 单元计算正确。
- EXP 结果写回目标寄存器。
- `LD -> EXP -> ST` 能把结果写回 GlobalMem。

## 结论

跨周期保存的控制信息必须使用寄存器。执行单元延迟增加后，原本被简单测试掩盖的写回元数据生命周期问题会直接暴露。
