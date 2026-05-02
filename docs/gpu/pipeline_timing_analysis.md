# GPU 流水线时序分析

日期：2026-05-02

本文记录了两层基于工具的时序分析过程，用于判断 toy GPU 应优先在哪些位置切分流水线：

1. 第一层：Yosys 结构综合 + 最长拓扑路径（LTP），用于预筛选。
2. 第二层：映射到 `sky130_fd_sc_hd` 标准单元库后，用 OpenSTA 跑 pre-layout STA。

第一层不包含工艺库、布局布线与线延迟，因此结论用于相对排序。第二层开始引入标准单元
延迟，但仍然没有布局布线寄生参数，所以结论依然是“pre-layout 粗估”，不是最终签核时序。

## 工具

已验证版本：

```text
Yosys 0.33
OpenLane v2.3.10
OpenROAD edf00dff99f6c40d67a30c0e22a8191c5d2ed9d6
OpenSTA 2.6.0
```

OpenLane/OpenROAD/OpenSTA 环境进入方式：

```bash
. /nix/var/nix/profiles/default/etc/profile.d/nix-daemon.sh
cd /opt/openlane2
nix-shell
```

## 第一层：结构分析

## 命令

生成 RTL：

```bash
sbt "runMain top.Elaborate"
```

检查 GPU `SM` 层级中的组合环：

```bash
yosys -p '
  read_verilog -sv generated/gpu/yosys/*.sv
  hierarchy -top SM
  proc; opt
  scc
' | rg 'SCC|Found 0'
```

对模块执行最长拓扑路径与结构统计：

```bash
yosys -p '
  read_verilog -sv generated/gpu/yosys/*.sv
  hierarchy -top SM
  proc; opt; flatten; opt
  ltp -noff
  stat
'
```

同样命令分别对以下顶层模块执行：

```text
SM
SharedRegisterFile
InstructionDispatcher
SMSubPartition
SFU
CudaCore
WarpScheduler
```

`ltp -noff` 会在路径搜索中排除触发器单元。若不加 `-noff`，计数器与状态寄存器的反馈路径会主导报告，降低对流水线切分决策的参考价值。

### 什么是 LTP 长度

`LTP` 是 Yosys 的 `Longest Topological Path`，即最长拓扑路径。这里的
`length` 不是物理时间，也不是纳秒，而是 Yosys 在当前网表里沿着一条组合路径
经过的“节点步数”或“拓扑层数”。

可以把它理解为：

- 路径越长，说明组合逻辑跨越的比较器、mux、算术单元、存储器读口等结构越多。
- `length` 越大，通常越可能成为后续真实 STA 中的候选关键路径。
- 但它**不等于**真实延迟，因为不同 cell 的物理代价并不一样。

例如：

- 一个 `$mul` 在真实工艺里通常比一个 `$and` 慢很多。
- 一个 memory read、宽 mux 或高扇出网络，物理代价也可能明显大于若干简单逻辑门。

因此本文中的 `LTP length=40` 只能解释为：

```text
这条路径在结构上跨越的组合逻辑层次很多，
比 length=5 或 length=11 的模块更值得优先关注。
```

不能解释为：

```text
它一定比另一条路径慢 8 倍，
或者它的真实延迟就是某个固定纳秒值。
```

所以这里把 `LTP` 用作**预筛选指标**：先找“结构上明显过长”的路径，再进入
OpenROAD/OpenSTA，用工艺库和时钟约束确认真实 critical path。

## 结果

组合环检查结果：

```text
Found 0 SCCs in module CudaCore.
Found 0 SCCs in module InstructionDispatcher.
Found 0 SCCs in module SFU.
Found 0 SCCs in module SM.
Found 0 SCCs in module SMSubPartition.
Found 0 SCCs in module SMSubPartition_1.
Found 0 SCCs in module SharedRegisterFile.
Found 0 SCCs in module WarpScheduler.
Found 0 SCCs.
```

模块对比：

| 模块 | LTP 长度 | Cells | `$and` | `$eq` | `$mux` | `$pmux` | `$mul` |
|---|---:|---:|---:|---:|---:|---:|---:|
| `SM` | 40 | 6035 | 2408 | 278 | 2147 | 282 | 16 |
| `SharedRegisterFile` | 13 | 6202 | 2396 | 796 | 1880 | 536 | 0 |
| `InstructionDispatcher` | 16 | 417 | 66 | 18 | 187 | 2 | 0 |
| `SMSubPartition` | 11 | 171 | 14 | 24 | 29 | 0 | 8 |
| `WarpScheduler` | 6 | 11 | 2 | 0 | 1 | 0 | 0 |
| `SFU` | 5 | 17 | 0 | 1 | 2 | 0 | 1 |
| `CudaCore` | 5 | 13 | 0 | 3 | 3 | 0 | 1 |

`SM` 内部最长路径：

```text
Longest topological path in SM (length=40)
```

该路径跨越的模块链路：

```text
SMSubPartition scheduler grant
  -> InstructionDispatcher selected-warp/decode path
  -> SharedRegisterFile read and same-cycle forwarding muxes
  -> SMSubPartition SFU operand/index path
```

Yosys 报告中的关键路径节点：

```text
SMSubPartition_1.scheduler._grantIdx_T
SMSubPartition_1._scheduler_io_grant_0
SMSubPartition_1.io_selectedWarp_bits
dispatcher selected-warp/decode logic
dispatcher.io_regRdAddr_5_warpId
regFile forwarding and read mux chain
dispatcher.io_regRdData_5_rs1
SMSubPartition_1.CudaCore_1.io_rs1
SMSubPartition_1.SFU_1._x_shifted_T
SMSubPartition_1.SFU_1.index
```

## 第二层：映射到标准单元库后的 pre-layout STA

这一层的目的不是签核，而是回答两个更具体的问题：

- 在引入真实标准单元延迟后，第一层的排序是否仍然成立？
- 哪些模块已经大到值得单独切流水，哪些模块其实还很轻？

### 一次性准备

安装与获取 PDK：

```bash
cd /opt/openlane2
python3 -c 'import openlane.common as c; print(c.get_opdks_rev())'
volare fetch 0fe599b2afb6708d281543108caf8310912f54af \
  --pdk sky130 \
  -l default \
  -l sky130_fd_sc_hd
```

本次分析使用的 slow corner Liberty：

```text
/root/.volare/volare/sky130/versions/0fe599b2afb6708d281543108caf8310912f54af/sky130A/libs.ref/sky130_fd_sc_hd/lib/sky130_fd_sc_hd__ss_100C_1v60.lib
```

综合映射时使用的 TT Liberty：

```text
/root/.volare/volare/sky130/versions/0fe599b2afb6708d281543108caf8310912f54af/sky130A/libs.ref/sky130_fd_sc_hd/lib/sky130_fd_sc_hd__tt_025C_1v80.lib
```

### 命令

先把 `SM` 映射到 sky130 标准单元：

```bash
mkdir -p generated/sta

LIB_TT=/root/.volare/volare/sky130/versions/0fe599b2afb6708d281543108caf8310912f54af/sky130A/libs.ref/sky130_fd_sc_hd/lib/sky130_fd_sc_hd__tt_025C_1v80.lib

yosys -ql generated/sta/yosys_sm_map.log -p "
  read_verilog -sv generated/gpu/yosys/*.sv
  hierarchy -top SM
  proc; opt; flatten; opt
  memory_map; techmap; opt
  dfflibmap -liberty $LIB_TT
  abc -liberty $LIB_TT
  clean
  write_verilog -noattr generated/sta/SM_mapped.v
"
```

然后在 slow corner 上跑一次最小约束的 setup 分析：

```bash
cd /opt/openlane2
/root/.nix-profile/bin/nix-shell --run 'sta <<'"'"'EOF'"'"'
read_liberty /root/.volare/volare/sky130/versions/0fe599b2afb6708d281543108caf8310912f54af/sky130A/libs.ref/sky130_fd_sc_hd/lib/sky130_fd_sc_hd__ss_100C_1v60.lib
read_verilog /root/vibe-processor/generated/sta/SM_mapped.v
link_design SM
create_clock -name clk -period 10 [get_ports clock]
set_case_analysis 0 [get_ports reset]
set_input_delay 0 -clock clk [all_inputs -no_clocks]
set_output_delay 0 -clock clk [all_outputs]
report_worst_slack -max
report_checks -path_delay max -group_count 2 -digits 3
EOF'
```

同样的流程对以下模块分别执行：

```text
SharedRegisterFile
InstructionDispatcher
SMSubPartition
SFU
CudaCore
WarpScheduler
```

说明：

- `10ns` 时钟只是统一比较用的参考约束，不表示当前设计真实可跑 `100MHz`。
- 这里没有布局布线寄生参数，因此结果是 pre-layout coarse estimate。
- 对于像 `SharedRegisterFile`、`SFU` 这种“组合逻辑从输入直达输出/寄存器”的模块，
  `input -> output` 或 `input -> reg` 路径比默认 `reg -> reg` 更有参考价值。

### 第二层结果

`SM` 顶层 slow corner setup 结果：

| 模块 | 约束 | worst slack | 最长路径 data arrival |
|---|---:|---:|---:|
| `SM` | `10ns` | `-322.09ns` | `331.882ns` |

顶层 `SM` 的结论很明确：单周期路径远超 `10ns`。但由于综合时做了 flatten，OpenSTA
报告里的实例名已经被压平成匿名网表节点，只能证明“`SM` 确实极重”，不适合直接用来定位
源码级切分点。

因此继续看单模块对比。在同样的 `10ns` 约束、同样的 slow corner 下，得到如下结果：

| 模块 | 最坏路径类型 | worst slack | 最长路径 data arrival | 结论 |
|---|---|---:|---:|---|
| `SharedRegisterFile` | `input -> output` | `-27.18ns` | `37.184ns` | 最重，优先切 |
| `SFU` | `input -> reg` | `-27.15ns` | `36.823ns` | 很重，应流水化 |
| `SMSubPartition` | `input -> reg` | `-24.52ns` | `34.343ns` | 执行级整体偏重 |
| `CudaCore` | `input -> reg` | `-15.92ns` | `25.590ns` | 中等偏重 |
| `InstructionDispatcher` | `input -> reg` | `-15.40ns` | `25.107ns` | 中等偏重 |
| `WarpScheduler` | `reg -> reg` | `8.73ns` | `1.083ns` | 很轻，不急着切 |

### 关键路径片段

`SharedRegisterFile` 的最坏路径：

```text
Startpoint: io_rdAddr_1_laneId[1]
Endpoint:   io_rdData_1_rs1[0]
data arrival time: 37.184ns
```

这条路径是典型的“读地址 -> 读口选择/比较 -> 读数据输出”链，和第一层分析看到的
forwarding + read mux 结构完全一致。

`SFU` 的最坏路径：

```text
Startpoint: io_x[16]
Endpoint:   _4894_/D
data arrival time: 36.823ns
```

这条路径对应 `exp` 近似数据通路，也就是 `x -> index/range -> LUT/插值相关逻辑 -> 输出寄存器`。

`InstructionDispatcher` 的最坏路径：

```text
Startpoint: io_selectedWarp_1_bits[0]
Endpoint:   _2680_/D
data arrival time: 25.107ns
```

这条路径反映的是 selected warp 输入进入 decode / issue / 状态更新逻辑后的寄存器捕获延迟。

`SMSubPartition` 的最坏路径：

```text
Startpoint: io_coreRs1_1[16]
Endpoint:   _38294_/D
data arrival time: 34.343ns
```

这说明执行级整体并不轻，尤其是 operand 进入执行单元后的组合链较长。

## 结论

第一层和第二层的结论总体一致，但第二层把优先级进一步压实了：

- `WarpScheduler` 很轻，不是现在的瓶颈。
- `SharedRegisterFile` 是最该优先切的模块。
- `SFU` 已经重到接近 `SharedRegisterFile`，不能再当成“后置优化”。
- `InstructionDispatcher` 和 `CudaCore` 有压力，但优先级低于前两者。
- 顶层 `SM` 的关键长路径，本质上仍然是“调度/解码/读寄存器/执行”串得太长。

建议优先级：

1. 将 `InstructionDispatcher` -> `SharedRegisterFile` -> `SMSubPartition` 执行链拆成显式阶段：

   ```text
   schedule/fetch -> decode -> register read -> execute -> writeback
   ```

   这样可直接打断当前 `SM` 顶层最长路径。目前该路径在同一周期内串联了调度选择、
   解码、寄存器读/转发和执行输入逻辑。第一层 `LTP=40`，第二层 slow corner 下顶层
   `data arrival=331.882ns`，都说明这里必须拆。

2. 重构或流水化 `SharedRegisterFile` 的 forwarding。其独立结构规模最大：6202 cells、796 个等值比较器、1880 个 mux、536 个 priority mux，主要来自“所有读端口对所有写端口”进行同周期转发比较。

   第二层 slow corner 下，`SharedRegisterFile` 的最坏 `input -> output` 路径达到
   `37.184ns`，已经足以单独证明它值得切一拍。最直接的做法是把“读地址发出”和“读数据被执行级使用”拆成两个周期，必要时把 forwarding 也做成分层或分拍。

3. 把 `SFU` 从单拍近似运算改成显式多拍。

   第二层 slow corner 下，`SFU` 的 `input -> reg` 路径达到 `36.823ns`，和
   `SharedRegisterFile` 基本同量级。对 `exp` 路径，比较自然的切法是：

   ```text
   range/index -> LUT read -> interpolate/accumulate -> writeback
   ```

4. 在 `InstructionDispatcher` 的 selected-warp/decode 之后切分。其独立 LTP 为 16，
   第二层 slow corner 下 `input -> reg` 路径约 `25.107ns`，也明显超过 `10ns`。
   但它的优先级低于 `SharedRegisterFile` 和 `SFU`。

5. `CudaCore` 可以保留在第二批处理。它的第二层 slow corner `input -> reg` 路径约
   `25.590ns`，确实不轻，但仍弱于 `SharedRegisterFile` / `SFU` / `SMSubPartition`
   这三者。

如果后续要继续提高分析精度，下一步应做的是：

1. 给 `SM` 或 `ToyGpuTop` 增加正式的 OpenLane design config。
2. 提供更完整的 SDC，而不是这里只用于对比的最小约束。
3. 跑 OpenROAD 的布局布线，再看带寄生参数的 `report_checks`。

那时拿到的才会是更接近最终实现的 Fmax/critical path 结论。
