# Vibe Processor - 教学用 NPU 和 GPU

基于 Chisel 的教学用 AI 加速器实现，包括昇腾 NPU 和英伟达 GPU 的简化版本。

## 🎯 项目特点

- **NPU (昇腾风格)**: 收缩阵列架构，支持矩阵乘法和向量运算
- **GPU (英伟达风格)**: SIMT 架构，Warp 调度，多 SM 并行
- **DMA-Compute Overlap**: 非阻塞 DMA，流水线优化，实际加速比 1.22×
- **完整文档**: 架构说明、ISA 定义、性能分析

## 📊 性能亮点

| 指标 | 顺序执行 | 流水线 Overlap | 提升 |
|------|---------|---------------|------|
| 总周期数 | 557 | 455 | **-18.3%** |
| 重叠率 | 0.0% | **24.1%** | **+24.1%** |
| 计算效率 | 8.1% | 9.9% | **+1.8%** |

## 🚀 快速开始

### 环境要求

- Scala 2.13+
- sbt 1.12+
- Verilator 5.0+
- Java 21+

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
# 生成 NPU Verilog
sbt "runMain top.Elaborate --target npu"

# 生成 GPU Verilog
sbt "runMain top.Elaborate --target gpu"
```

## 📚 文档

### 核心文档

- **[文档索引](docs/DOCUMENTATION_INDEX.md)** - 所有文档的入口
- **[项目概览](docs/README.md)** - 项目介绍和架构概览

### NPU 文档

- **[NPU 架构](docs/npu/architecture.md)** - 收缩阵列、DMA、多核并行
- **[DMA-Compute Overlap](docs/npu/dma_overlap.md)** - 非阻塞 DMA、双缓冲、性能优化
- **[性能测量](docs/npu/performance_measurement.md)** - 实际加速比 1.22×，重叠率 24.1%
- **[架构差异](docs/npu/architecture_differences.md)** - 与真实昇腾的差距分析

### GPU 文档

- **[GPU 架构](docs/gpu/architecture.md)** - SIMT、Warp、SM 架构
- **[Warp 调度](docs/gpu/warp_scheduling.md)** - 调度策略和性能优化
- **[双调度器](docs/gpu/dual_scheduler_summary.md)** - 双发射架构

### 其他文档

- **[ISA 定义](docs/isa.md)** - 指令集架构
- **[性能对比](docs/performance_comparison.md)** - NPU vs GPU
- **[交互式可视化](docs/interactive/index.html)** - 架构图可视化

## 🏗️ 项目结构

```
vibe-processor/
├── src/
│   ├── main/scala/
│   │   ├── ascend/          # NPU 实现
│   │   │   ├── AiCore.scala
│   │   │   ├── SystolicArray.scala
│   │   │   ├── CubeUnit.scala
│   │   │   └── ...
│   │   ├── gpu/             # GPU 实现
│   │   │   ├── SM.scala
│   │   │   ├── WarpScheduler.scala
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
- ✅ **向量单元** - VECADD, RELU 等操作
- ✅ **DMA 引擎** - 非阻塞 DMA，支持 Overlap
- ✅ **多核并行** - 2 个 AiCore，独立执行
- ✅ **存储层次** - L0/UB/L2/HBM 四级存储
- ✅ **性能计数器** - 精确的性能统计

### GPU (英伟达风格)

- ✅ **SIMT 架构** - 32 线程 Warp
- ✅ **多 SM** - 2 个 SM，独立调度
- ✅ **Warp 调度器** - 双调度器，支持双发射
- ✅ **寄存器文件** - 每线程独立寄存器
- ✅ **共享内存** - SM 内共享
- ✅ **全局内存** - 统一地址空间

## 📈 性能优化

### DMA-Compute Overlap

**实现：**
- 非阻塞 DMA 指令（DMA_LOAD/DMA_STORE/DMA_WAIT）
- DMA 请求队列（深度 4）
- UB 双端口分离（Scalar + DMA）
- L0 双缓冲架构

**效果：**
- 实际加速比：**1.22×**
- 重叠率：**24.1%**
- 节省周期：**102 个（18.3%）**

**优化空间：**
- 理论加速比：1.83×
- 优化潜力：+50%

## 🧪 测试覆盖

```
总测试数：     21 个
通过：         21 个 ✅
通过率：       100% 🎉
```

**测试类型：**
- 单元测试：CubeUnit, SystolicArray, VectorUnit, PE
- 集成测试：IntegrationTest, MultiCoreTest
- 性能测试：OverlapBenchmark, OverlapTest
- 功能测试：LargeMatmulTest

## 🔬 学习价值

### 硬件设计

- 非阻塞指令设计
- 队列管理与流控
- 双端口存储器
- 性能计数器实现

### 系统优化

- DMA-Compute Overlap 原理
- 流水线设计
- 双缓冲技术
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
| 收缩阵列 | 8×8 | 16×16 或更大 | 4× |
| AI Core 数量 | 2 | 32 | 16× |
| L2 缓存 | 2KB | 8MB | 4000× |
| 峰值算力 | ~0.004 TFLOPS | 256 TFLOPS | 64000× |

### GPU (vs NVIDIA A100)

| 特性 | 玩具版本 | NVIDIA A100 | 差距 |
|------|---------|-------------|------|
| SM 数量 | 2 | 108 | 54× |
| Warp 大小 | 32 | 32 | 1× |
| 寄存器/线程 | 8 | 255 | 32× |
| 共享内存 | 256B | 164KB | 656× |
| 峰值算力 | ~0.001 TFLOPS | 19.5 TFLOPS | 19500× |

## 🚀 未来工作

### 短期（1-2 周）

- 优化 buffer 切换逻辑
- 增加 DMA 队列深度
- 实现自动地址管理

### 中期（1 个月）

- DMA 优先级调度
- 多级流水线优化
- 增大 Tile 尺寸
- 增加 L2 缓存

### 长期（2-3 个月）

- 与真实昇腾对比
- 性能优化指南
- 更多优化技术探索

## 📝 引用

如果这个项目对你有帮助，欢迎引用：

```
Vibe Processor - Educational NPU and GPU Implementation
https://github.com/your-repo/vibe-processor
```

## 📄 许可证

本项目仅用于教学目的。

## 🙏 致谢

感谢所有为这个项目做出贡献的人！

---

**版本：** v1.0  
**完成日期：** 2026-05-01  
**状态：** ✅ 100% 完成  
**测试通过率：** ✅ 100%  
**性能提升：** ✅ 1.22× 加速比
