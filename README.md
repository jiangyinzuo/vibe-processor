# Vibe Processor - 教学用 NPU 和 GPU

基于 Chisel 的教学用 AI 加速器实现，包括昇腾 NPU 和 NVIDIA GPU 的简化模型。

## 快速开始

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

### 代码格式化

项目使用 `sbt-scalafmt` 固定 Scala/Chisel 格式，配置见 `.scalafmt.conf`。

```bash
# 格式化 Scala 源码、测试和 build.sbt
sbt "scalafmtAll; scalafmtSbt"

# CI / 提交前检查格式
sbt "scalafmtCheckAll; scalafmtSbtCheck"
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
