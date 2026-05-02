# Vibe Processor - 教学用 NPU 和 GPU

基于 Chisel 的教学用 AI 加速器实现，包括昇腾 NPU 和英伟达 GPU 的简化版本。

## 🎯 项目特点

- **NPU (昇腾风格)**: 收缩阵列架构，支持矩阵乘法和向量运算
- **GPU (英伟达风格)**: SIMT 架构，Warp 调度，多 SM 并行，共享 CUDA Core
- **DMA-Compute Overlap**: 非阻塞 DMA，流水线优化，实际加速比 1.22×
- **完整文档**: 架构说明、ISA 定义、性能分析

## 📊 性能亮点

### NPU - DMA-Compute Overlap
| 指标 | 顺序执行 | 流水线 Overlap | 提升 |
|------|---------|---------------|------|
| 总周期数 | 557 | 455 | **-18.3%** |
| 重叠率 | 0.0% | **24.1%** | **+24.1%** |

### GPU - 共享架构重构
| 指标 | 原始架构 | 共享架构 | 提升 |
|------|---------|---------|------|
| 资源利用率 | 6.25% | 25-100% | **4-16× 提升** |
| SM 利用率 | N/A | 85.7% | **高效** |

## 🚀 快速开始

### 环境要求

- JDK 17+（推荐 JDK 21 LTS）
- sbt 1.12.10（由 `project/build.properties` 固定）
- Scala 2.13.18（由 `build.sbt` 固定）
- Chisel 7.9.0（由 `build.sbt` 自动下载）
- Verilator 5.0+（ChiselSim/svsim 测试需要）
- 可选时序分析工具：Yosys、OpenLane 2 / OpenROAD / OpenSTA

### 安装 Chisel 环境

Chisel 本身是 Scala/sbt 依赖，不需要单独安装；首次运行 `sbt` 时会根据 `build.sbt` 自动下载 Chisel 7.9.0、Chisel compiler plugin 和 ScalaTest。

**Linux / WSL (Ubuntu/Debian, x86_64 或 ARM64)：**

```bash
sudo apt-get update
sudo apt-get install -y curl gzip git make g++ verilator

case "$(uname -m)" in
  x86_64)
    CS_URL="https://github.com/coursier/coursier/releases/latest/download/cs-x86_64-pc-linux.gz"
    ;;
  aarch64|arm64)
    CS_URL="https://github.com/VirtusLab/coursier-m1/releases/latest/download/cs-aarch64-pc-linux.gz"
    ;;
  *)
    echo "Unsupported architecture: $(uname -m)" >&2
    exit 1
    ;;
esac

curl -fL "$CS_URL" | gzip -d > cs
chmod +x cs
./cs setup --jvm 21
```

执行完 `cs setup` 后，重新打开终端，或临时补上 coursier 的 bin 目录：

```bash
export PATH="$PATH:$HOME/.local/share/coursier/bin"
```

**macOS (Homebrew)：**

```bash
brew install coursier/formulas/coursier verilator
cs setup --jvm 21
```

验证环境：

```bash
java -version
sbt --version
verilator --version
sbt "testOnly ascend.PETest"
```

### 安装时序分析工具

用于后续判断流水线切分点的两层工具：

- **Yosys**：快速综合 RTL，查看资源统计、结构问题和粗略组合路径。
- **OpenLane 2 / OpenROAD / OpenSTA**：跑 ASIC 风格综合、布局布线和静态时序分析（STA）。

Linux / WSL (Ubuntu/Debian)：

```bash
sudo apt-get update
sudo apt-get install -y yosys graphviz curl git xz-utils

# OpenLane 2 官方推荐 Nix 安装方式；不要用 apt 安装 Nix。
curl --proto '=https' --tlsv1.2 -sSf -L https://install.determinate.systems/nix | sh -s -- install --no-confirm --extra-conf "
extra-substituters = https://openlane.cachix.org
extra-trusted-public-keys = openlane.cachix.org-1:qqdwh+QMNGmZAuyeQJTH9ErW57OWSvdtuwfBKdS254E=
"

# 当前 shell 立即启用 nix；新终端通常会自动加载。
. /nix/var/nix/profiles/default/etc/profile.d/nix-daemon.sh

sudo rm -rf /opt/openlane2
sudo git clone --depth 1 https://github.com/efabless/openlane2 /opt/openlane2
```

验证安装：

```bash
yosys -V

. /nix/var/nix/profiles/default/etc/profile.d/nix-daemon.sh
cd /opt/openlane2
nix-shell --run 'openlane --version && openroad -version && sta -version'
```

当前环境验证版本：

```text
Yosys 0.33
OpenLane v2.3.10
OpenSTA 2.6.0
```

### RTL 结构和时序分析

先生成适合 Yosys 读取的 Verilog：

```bash
sbt "runMain top.Elaborate"
```

Yosys 粗筛 GPU RTL：

```bash
yosys -p '
  read_verilog -sv generated/gpu/yosys/*.sv
  hierarchy -top ToyGpuTop
  proc; opt
  check
  stat
'
```

查看某个模块的粗略最长拓扑路径：

```bash
yosys -p '
  read_verilog -sv generated/gpu/yosys/*.sv
  hierarchy -top SM
  proc; opt; flatten; opt
  ltp -noff
'
```

`ltp -noff` 会自动排除触发器 cell，避免把寄存器自身的反馈路径当成组合路径。它不使用工艺库和线延迟，只能作为流水线切分的早期线索；真正的 Fmax 和 critical path 需要 OpenROAD/OpenSTA 在具体工艺库、SDC 时钟约束和后端流程下报告。

进入 OpenLane 2 工具环境：

```bash
. /nix/var/nix/profiles/default/etc/profile.d/nix-daemon.sh
cd /opt/openlane2
nix-shell
```

进入环境后可以使用：

```bash
openlane --version
openroad -version
sta -version
```

下一步若要得到真实 STA 报告，需要为 `ToyGpuTop` 或单个子模块准备 OpenLane design config、SDC 时钟约束和目标 PDK，然后用 OpenROAD/OpenSTA 查看 `report_checks` 的 critical path。

### 运行测试

```bash
# 运行所有测试
sbt test

# 运行 NPU 测试
sbt "testOnly ascend.*"

# 运行 GPU 测试
sbt "testOnly gpu.*"

# 运行性能测试
sbt "testOnly ascend.OverlapBenchmark"
```

### 生成 Verilog

```bash
# 同时生成 NPU 和 GPU Verilog
sbt "runMain top.Elaborate"
```

生成目录：

```text
generated/ascend/
generated/ascend/yosys/
generated/gpu/
generated/gpu/yosys/
```

## 📚 文档

### 核心文档

- **[项目状态](PROJECT_STATUS.md)** - 完成度、测试状态、性能数据
- **[项目概览](docs/README.md)** - 项目介绍和架构概览
- **[ISA 定义](docs/isa.md)** - 指令集架构

### NPU 文档

- **[NPU 架构](docs/npu/architecture.md)** - 收缩阵列、DMA、多核并行
- **[DMA-Compute Overlap](docs/npu/dma_overlap.md)** - 非阻塞 DMA、双缓冲、性能优化
- **[性能测量](docs/npu/performance_measurement.md)** - 实际加速比 1.22×，重叠率 24.1%

### GPU 文档

- **[GPU 架构](docs/gpu/architecture.md)** - SIMT、Warp、SM 架构
- **[架构对比](docs/gpu/architecture_comparison.md)** - 玩具 vs 真实 GPU
- **[共享架构总结](docs/gpu/shared_architecture_summary.md)** - 重构设计和性能提升
- **[Warp 调度](docs/gpu/warp_scheduling.md)** - 调度策略和性能优化
- **[双调度器](docs/gpu/dual_scheduler_summary.md)** - 双发射架构
- **[流水线时序分析](docs/gpu/pipeline_timing_analysis.md)** - Yosys 粗筛、最长拓扑路径和切分优先级

## 🏗️ 项目结构

```
vibe-processor/
├── src/
│   ├── main/scala/
│   │   ├── ascend/          # NPU 实现
│   │   │   ├── AiCore.scala
│   │   │   ├── SystolicArray.scala
│   │   │   └── ...
│   │   ├── gpu/             # GPU 实现
│   │   │   ├── SM.scala
│   │   │   ├── SMSubPartition.scala
│   │   │   ├── WarpContext.scala
│   │   │   ├── SharedRegisterFile.scala
│   │   │   └── ...
│   │   └── common/          # 共享组件
│   └── test/scala/          # 测试
│       ├── ascend/
│       └── gpu/
├── docs/                    # 文档
│   ├── npu/
│   ├── gpu/
│   └── diagrams/
└── build/                   # 生成的 Verilog
```

## 🎓 核心特性

### NPU (昇腾风格)

- ✅ **8×8 收缩阵列** - 矩阵乘法加速
- ✅ **AIC/AIV 解耦** - Cube 与 Vector 走独立执行核心
- ✅ **MTE 多通路** - MTE1/MTE2/MTE3 分别覆盖 UB→L0、L2↔UB、L0C→UB
- ✅ **多核并行** - 2 个 AiCore，独立执行
- ✅ **真实 Local Memory 层次** - UB、L1 staging、L0A/L0B/L0C 分层建模
- ✅ **性能计数器** - 精确的性能统计

### GPU (英伟达风格)

- ✅ **SIMT 架构** - Warp 执行模型
- ✅ **多 SM** - 4 个 SM，独立调度
- ✅ **共享 CUDA Core** - SM 级别共享资源（符合真实 GPU 设计）
- ✅ **双 Warp 调度器** - 支持双发射，2× 性能提升
- ✅ **共享寄存器文件** - 多端口访问，高效写回
- ✅ **全局内存** - 统一地址空间

## 📈 性能优化

### NPU - DMA-Compute Overlap

**实现：**
- 非阻塞 DMA 指令（DMA_LOAD/DMA_STORE/DMA_WAIT）
- DMA 请求队列（深度 4）
- UB 双端口分离（本地 MTE/AIV + MTE2）
- AIC 侧 L0A/L0B tile FIFO，支持 LOAD/MATMUL 重叠
- MTE1/MTE2/MTE3 多通路传输

**效果：**
- 实际加速比：**1.22×**
- 重叠率：**24.1%**
- 节省周期：**102 个（18.3%）**

### GPU - 共享架构重构

**实现：**
- CUDA Core 作为 SM 级别共享资源
- Warp 只保存轻量级执行上下文
- 内存数据缓冲解决组合逻辑问题
- 写端口冲突处理

**效果：**
- 资源利用率：从 **6.25%** 提升到 **25-100%**
- SM 利用率：**85.7%**
- 架构模型：符合真实 GPU 设计

## 🧪 测试覆盖

```
总测试数：     48 个
通过：         48 个 ✅
通过率：       100% 🎉
```

**NPU 测试 (23)：** IntegrationTest, Pipeline3Test, TripleBufferTest, PerfCounterTest, OverlapBenchmark, CubeUnitTest, SystolicArrayTest, VectorUnitTest, MultiCoreTest 等

**GPU 测试 (25)：** GpuIntegrationTest, DualSchedulerTest, SharedArchDebug, InstructionDispatcherMultiIssueTest, QuickSharedArchTest, CudaCoreTest 等

## 🔬 学习价值

### 硬件设计

- 非阻塞指令设计
- 队列管理与流控
- 双端口存储器
- 共享资源架构
- 性能计数器实现

### 系统优化

- DMA-Compute Overlap 原理
- 流水线设计
- 双缓冲技术
- 资源利用率优化
- 性能分析方法

### 工程实践

- Chisel HDL 编程
- 测试驱动开发
- 性能测量与分析
- 技术文档编写

## 📊 与真实硬件的差距

### NPU (vs 昇腾 910)

| 特性 | 玩具版本 | 真实昇腾 910 | 差距 |
|------|---------|-------------|------|
| 收缩阵列 | 8×8 | 16×16+ | 4× |
| AI Core 数量 | 4 | 32 | 8× |
| 流水线级数 | 2 级 | 10-20 级 | 5-10× |
| 重叠率 | 24.1% | 80-90% | 3-4× |

### GPU (vs NVIDIA A100)

| 特性 | 玩具版本 | NVIDIA A100 | 差距 |
|------|---------|-------------|------|
| SM 数量 | 4 | 108 | 27× |
| Warp 大小 | 4 | 32 | 8× |
| 调度器/SM | 2 | 4 | 2× |
| 架构模型 | ✅ 共享 CUDA Core | ✅ 共享 CUDA Core | **一致** |

## 📝 引用

如果这个项目对你有帮助，欢迎引用：

```
Vibe Processor - Educational NPU and GPU Implementation
https://github.com/your-repo/vibe-processor
```

## 📄 许可证

本项目仅用于教学目的。

---

**版本：** v2.0  
**最后更新：** 2026-05-02  
**状态：** ✅ 100% 完成  
**测试通过率：** ✅ 100% (37/37)  
**性能提升：** ✅ NPU 1.22× 加速比，GPU 4-16× 资源利用率提升
