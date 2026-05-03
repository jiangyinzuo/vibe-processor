# NPU 流水线时序分析

本文记录 toy NPU 在 CubeCore/VectorCore/MTE 解耦后的两层工具分析结果，用于判断后续流水线应该优先切在哪里，并记录已完成的流水线切分验证结果。

结论先行：

1. 已完成第一步切分：`CubeCore` 的 tile issue / launch 路径拆成两拍。
2. 已完成第二步切分：`CubeUnit` 启动时锁存 L0A/L0B tile，切断 L0 FIFO 到 SystolicArray/PE 的长组合路径。
3. 切分后功能测试、Verilog elaboration 和 Yosys `check` 均通过。
4. 当前系统级 Fmax 粗估从约 `3.5MHz` 提升到约 `7.3MHz`；下一步若继续提频，应优先切 `SystolicArray` / `PE` 内部乘加路径。
5. 当前不应该优先切 `ScalarUnit`。它在第二层 pre-layout STA 下可以满足 `10ns` 参考约束。
6. 这些数据是 pre-layout 粗估，不是最终签核 Fmax；它们适合指导早期流水线切分点选择。

## 环境

```text
Yosys 0.33
OpenSTA 2.6.0
Liberty: sky130_fd_sc_hd slow corner, ss_100C_1v60
Clock reference constraint: 10ns
```

使用 `sbt "runMain top.Elaborate"` 生成 Verilog 后分析 `generated/ascend/yosys/*.sv`。

## 第一层：Yosys LTP 粗筛

执行命令：

```bash
for top in ToyAscendTop AiCore CubeCore VectorCore Mte1 Mte2 Mte3 ScalarUnit CubeUnit SystolicArray PE VectorUnit UnifiedBuffer; do
  yosys -ql "/tmp/yosys_${top}.log" -p "
    read_verilog -sv generated/ascend/yosys/*.sv
    hierarchy -top $top
    proc; opt; flatten; opt
    ltp -noff
    stat
  "
done
```

`LTP` 是 `Longest Topological Path`，即最长拓扑路径。这里的 `length` 不是纳秒，也不是物理时间，而是 Yosys 在组合网表里统计出的最长结构层数。它只能作为早期筛选指标：length 越大，越可能成为后续真实 STA 的候选关键路径。

### LTP 结果

| 模块 | LTP 长度 | Cells | `$and` | `$eq` | `$mux` | `$pmux` | `$mul` |
|---|---:|---:|---:|---:|---:|---:|---:|
| `ToyAscendTop` | 16 | 4477 | 592 | 351 | 335 | 323 | 128 |
| `AiCore` | 16 | 2233 | 294 | 180 | 159 | 162 | 64 |
| `ScalarUnit` | 13 | 120 | 13 | 21 | 26 | 2 | 0 |
| `VectorCore` | 10 | 126 | 6 | 9 | 39 | 0 | 0 |
| `CubeCore` | 8 | 1633 | 229 | 126 | 32 | 151 | 64 |
| `CubeUnit` | 7 | 719 | 98 | 106 | 30 | 10 | 64 |
| `SystolicArray` | 7 | 583 | 83 | 52 | 16 | 1 | 64 |
| `Mte2` | 7 | 92 | 7 | 7 | 28 | 3 | 0 |
| `Mte1` | 6 | 49 | 4 | 5 | 8 | 1 | 0 |
| `Mte3` | 4 | 26 | 2 | 3 | 6 | 1 | 0 |
| `VectorUnit` | 3 | 46 | 2 | 1 | 16 | 0 | 0 |
| `PE` | 2 | 5 | 0 | 0 | 0 | 0 | 1 |
| `UnifiedBuffer` | 2 | 14 | 2 | 0 | 6 | 0 | 0 |

第一层结论：NPU 没有出现 GPU 之前那种 LTP=40 的超长结构路径。顶层和 `AiCore` 的 LTP=16，主要来自 Scalar 控制信号到性能计数器的组合条件；但 LTP 低不代表真实延迟一定低，因为 `CubeCore` / `SystolicArray` 内部有大量乘法器和展开寄存器负载。

## 第二层：标准单元映射后的 pre-layout STA

先用 Yosys 映射到 sky130 标准单元：

```bash
mkdir -p generated/sta/npu

LIB_TT=/root/.volare/volare/sky130/versions/0fe599b2afb6708d281543108caf8310912f54af/sky130A/libs.ref/sky130_fd_sc_hd/lib/sky130_fd_sc_hd__tt_025C_1v80.lib

yosys -ql generated/sta/npu/yosys_AiCore_map.log -p "
  read_verilog -sv generated/ascend/yosys/*.sv
  hierarchy -top AiCore
  proc; opt; flatten; opt
  memory_map; techmap; opt
  dfflibmap -liberty $LIB_TT
  abc -liberty $LIB_TT
  clean
  write_verilog -noattr generated/sta/npu/AiCore_mapped.v
"
```

然后在 slow corner 下跑 OpenSTA：

```bash
cd /opt/openlane2
/root/.nix-profile/bin/nix-shell --run 'sta <<'"'"'EOF'"'"'
read_liberty /root/.volare/volare/sky130/versions/0fe599b2afb6708d281543108caf8310912f54af/sky130A/libs.ref/sky130_fd_sc_hd/lib/sky130_fd_sc_hd__ss_100C_1v60.lib
read_verilog /root/vibe-processor/generated/sta/npu/AiCore_mapped.v
link_design AiCore
create_clock -name clk -period 10 [get_ports clock]
set_case_analysis 0 [get_ports reset]
set_input_delay 0 -clock clk [all_inputs -no_clocks]
set_output_delay 0 -clock clk [all_outputs]
report_worst_slack -max
report_checks -path_delay max -group_count 3 -digits 3
EOF'
```

同样流程对以下模块执行：

```text
CubeCore
SystolicArray
ScalarUnit
VectorCore
Mte2
```

说明：

- `10ns` 只是统一比较用的参考约束，不表示当前设计真实目标就是 `100MHz`。
- 这里没有布局布线寄生参数，因此结果是 pre-layout coarse estimate。
- 顶层 flatten 后实例名会变成匿名 cell，适合判断量级，不适合直接签核。

### STA 结果

| 模块 | 约束 | worst slack | 最长路径 data arrival | 结论 |
|---|---:|---:|---:|---|
| `AiCore` | `10ns` | `-194.35ns` | `203.684ns` | 顶层不过，压力来自 CubeCore 侧展开逻辑 |
| `CubeCore` | `10ns` | `-194.33ns` | `203.655ns` | 当前最大瓶颈，优先切 |
| `SystolicArray` | `10ns` | `-51.03ns` | `60.893ns` | 计算阵列也明显偏重 |
| `VectorCore` | `10ns` | `-18.48ns` | `28.122ns` | 次要瓶颈 |
| `Mte2` | `10ns` | `-9.34ns` | `19.342ns` | 有压力，但低于 CubeCore/VectorCore |
| `ScalarUnit` | `10ns` | `+3.47ns` | `6.530ns` | 当前不需要优先切 |

### 关键路径定位

`AiCore` 顶层最长路径：

```text
scalar.state
  -> ScalarUnit control decode
  -> vectorCore.io_start
  -> perf_bubbleCycles increment condition
```

这条路径解释了为什么第一层 LTP 把 `AiCore` 顶层排在最前。但第二层的 `CubeCore` 独立结果和 `SystolicArray` 结果说明，真正需要优先优化的是 CubeCore/Cube 侧，而不是继续切 Scalar 控制器。

`CubeCore` 的 LTP 关键片段：

```text
l0aReady/l0bReady selected by computeSlot
  -> io.start && slotReady
  -> cube.io.start
  -> clear l0aReady/l0bReady for consumed slot
```

对应源码位置：

- `slotReadyAfterWrite` 与 ready-bit 管理：[CubeCore.scala](../../src/main/scala/ascend/CubeCore.scala#L62)
- `computeSlot` issue 和 `cube.io.start`：[CubeCore.scala](../../src/main/scala/ascend/CubeCore.scala#L89)

## 流水线切分建议

优先级：

1. **切 `CubeCore` tile issue/launch 路径。已实现。**
   把 “检查 `l0aReady/l0bReady` + 选择 `activeSlot` + 清 ready bit + `startCube`” 拆成两拍：
   - issue stage：检查 `computeSlot` 对应 tile 是否 ready，锁存 `issueSlot`
   - launch stage：清 ready bit，启动 Cube

   当前实现位于 [CubeCore.scala](../../src/main/scala/ascend/CubeCore.scala#L89)：`sIdle` 只检查 ready bit 并锁存 `issueSlot`，`sLaunch` 再更新 `activeSlot`、清 ready bit、递增 `computeSlot` 并拉高 `cube.io.start`。这样 `l0aReady/l0bReady` 选择不再和 Cube 启动、ready bit 清除处于同一拍。

2. **切 `CubeUnit` L0 输入快照。已实现。**
   CubeUnit 在启动时锁存 L0A/L0B tile，避免 L0 FIFO 直接组合驱动 SystolicArray。详细前后对比见 [CubeCore 真实化优化记录](cubecore_realism_optimization.md)。

3. **再切 `SystolicArray` / `PE`。**
   如果目标频率更高，需要继续把 PE 内乘加或阵列控制路径分拍。当前 `SystolicArray` 独立 data arrival 约 `59.6ns`，已经说明计算阵列不能长期保持单周期/浅流水假设。

4. **后续处理 `VectorCore`。**
   `VectorCore` 的路径约 `28.1ns`，主要是状态机与 vector 读写控制 mux 链。它会影响 vector 指令频率，但不是当前最大瓶颈。

5. **最后处理 `Mte2`。**
   `Mte2` 约 `19.3ns`，可通过地址生成寄存、读写阶段更细分来降低路径，但优先级低于 CubeCore/Cube。

5. **暂不优先切 `ScalarUnit`。**
   `ScalarUnit` 在当前参考约束下 slack 为正，继续切它不会显著改善全局瓶颈。

## 第一次切分后的验证

实现内容：

- `CubeCore` 状态机从 `sIdle -> sRun -> sDone` 改为 `sIdle -> sLaunch -> sRun -> sDone`。
- 新增 `issueSlot`，在 issue stage 锁存本次要消费的 tile slot。
- `sLaunch` 负责设置 `activeSlot`、清 `l0aReady/l0bReady`、推进 `computeSlot` 并启动 `CubeUnit`。
- `CubeUnit` launch 周期使用 `issueSlot` 读 L0A/L0B，后续运行周期使用 `activeSlot`，避免依赖同拍更新后的寄存器值。

功能验证命令：

```bash
sbt "testOnly ascend.CubeUnitTest ascend.IntegrationTest ascend.MultiCoreTest ascend.SpmdTest ascend.Pipeline3Test"
sbt "testOnly ascend.*"
sbt "runMain top.Elaborate"
yosys -q -p 'read_verilog -sv generated/ascend/yosys/*.sv; hierarchy -top ToyAscendTop; proc; opt; check'
```

验证结果：

| 命令 | 结果 |
|---|---|
| `sbt "testOnly ascend.CubeUnitTest ascend.IntegrationTest ascend.MultiCoreTest ascend.SpmdTest ascend.Pipeline3Test"` | 5 suites / 7 tests passed |
| `sbt "testOnly ascend.*"` | 13 suites / 24 tests passed |
| `sbt "runMain top.Elaborate"` | 生成 `generated/ascend/` 和 `generated/gpu/` 成功 |
| Yosys `check` | 通过，无报错输出 |

可观察性能变化：

| 测试 | 切分后结果 | 说明 |
|---|---:|---|
| 单核 8×8 MATMUL 集成测试 | 182 cycles | 多了 1 拍 CubeCore launch stage |
| 2 核 MATMUL | per-core total=182 cycles | 两个物理 AiCore 结果均正确 |
| SPMD `blockDim=4` on 2 cores | 367 cycles | 4 个逻辑 block 全部完成 |
| `Pipeline3Test` | 447 cycles, overlap=66 cycles | LOAD/MATMUL 重叠仍成立 |

切分后 LTP 粗筛命令：

```bash
for top in CubeCore AiCore; do
  yosys -ql "/tmp/yosys_${top}_post_pipeline.log" -p "
    read_verilog -sv generated/ascend/yosys/*.sv
    hierarchy -top $top
    proc; opt; flatten; opt
    ltp -noff
    stat
  "
done
```

切分后 LTP 结果：

| 模块 | LTP 长度 | 当前最长路径 |
|---|---:|---|
| `CubeCore` | 9 | `state` / `cubeReadSlot` → `CubeUnit` act feed → `SystolicArray` PE multiply/add |
| `AiCore` | 16 | `ScalarUnit` vector start decode → `perf_bubbleCycles` 条件 |

解释：`CubeCore` 的 LTP 数字没有直接下降，因为 LTP 只数拓扑层数，不计真实门延迟；第一步切分后最长结构路径从 ready-bit issue/launch 转移到 `CubeUnit` / `SystolicArray` 数据注入路径。第二步输入快照已经显著改善 STA 粗估，后续要继续提频，应切 `SystolicArray` / `PE`，而不是继续拆 `ScalarUnit`。

## 限制

- 当前结果没有布局布线寄生参数，不能当作最终 Fmax。
- sky130 是开源教学工艺，不代表真实 NPU 工艺。
- Chisel 生成的 Reg Vec 被展开成大量寄存器，`CubeCore` 的 STA 结果会受到高扇出控制和展开寄存器负载影响。
- 下一步若要得到更接近真实的频率结论，需要跑完整 OpenROAD floorplan/place/route，再看 routed STA。
