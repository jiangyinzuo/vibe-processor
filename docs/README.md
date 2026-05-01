# Vibe Processor 文档索引

## 📚 项目概述

本项目包含两个教学用 AI 加速器的 RTL 实现：

- **玩具版昇腾 NPU** — 2×AiCore，收缩阵列 + DMA + 三级存储层次
- **玩具版英伟达 GPU** — 4×SM，SIMT 执行模型 + 双 Warp 调度器

使用 Chisel 7 (Scala) 编写，Verilator (via svsim) 仿真，ScalaTest 验证。

**[🔗 交互式架构图](interactive/index.html)** - 可视化 NPU 和 GPU 架构

---

## 📖 文档结构

### NPU 文档

```
docs/npu/
├── architecture.md              # NPU 架构详解
├── architecture_differences.md  # 玩具 vs 真实昇腾差异
├── dma_overlap.md               # DMA-Compute Overlap 优化
├── performance_measurement.md   # 实际性能测量报告
└── implementation_summary.md    # 实现总结
```

**快速链接**：
- [NPU 架构](npu/architecture.md) - 收缩阵列、DMA、多核并行
- [架构差异](npu/architecture_differences.md) - 与真实昇腾的差距分析
- [DMA Overlap](npu/dma_overlap.md) - 非阻塞 DMA、双缓冲、性能优化
- [性能测量](npu/performance_measurement.md) - 实际加速比 1.22×，重叠率 24.1%

### GPU 文档

```
docs/gpu/
├── architecture.md              # GPU 架构详解
├── warp_scheduling.md           # Warp 调度机制详解
└── dual_scheduler_summary.md    # 双调度器实现总结
```

**快速链接**：
- [GPU 架构](gpu/architecture.md) - SIMT、Warp 调度、双调度器
- [Warp 调度](gpu/warp_scheduling.md) - Round-Robin、协作式调度、延迟隐藏
- [双调度器](gpu/dual_scheduler_summary.md) - 2-3× 性能提升

### 对比分析

```
docs/
├── performance_comparison.md    # NPU vs GPU 性能对比
└── isa.md                       # 指令集说明
```

**快速链接**：
- [性能对比](performance_comparison.md) - 矩阵乘法性能分析
- [指令集](isa.md) - NPU 和 GPU 指令格式

### 交互式文档

```
docs/interactive/
├── index.html                   # 交互式架构图
└── README.md                    # 使用说明
```

**快速链接**：
- [交互式架构图](interactive/index.html) - 可视化架构，支持模块导航

---

## 🗺️ 阅读路径

### 快速入门（30 分钟）

1. 本文档 - 了解项目结构
2. [交互式架构图](interactive/index.html) - 可视化架构
3. [指令集](isa.md) - 了解指令格式

### 深入理解 NPU（1.5 小时）

1. [NPU 架构](npu/architecture.md) - 收缩阵列、DMA、多核
2. [DMA Overlap](npu/dma_overlap.md) - 非阻塞 DMA、双缓冲优化
3. [架构差异](npu/architecture_differences.md) - 与真实昇腾对比
4. [性能对比](performance_comparison.md) - 性能分析

### 深入理解 GPU（1.5 小时）

1. [GPU 架构](gpu/architecture.md) - SIMT、双调度器
2. [Warp 调度](gpu/warp_scheduling.md) - 调度算法详解
3. [双调度器](gpu/dual_scheduler_summary.md) - 性能提升
4. [性能对比](performance_comparison.md) - 与 NPU 对比

### 性能优化（45 分钟）

1. [DMA Overlap](npu/dma_overlap.md) - 计算与传输重叠
2. [性能对比](performance_comparison.md) - 瓶颈分析
3. [双调度器](gpu/dual_scheduler_summary.md) - 并行优化
4. [架构差异](npu/architecture_differences.md) - 真实硬件优化

---

## 📊 文档统计

| 类别 | 文档数 | 总行数 |
|------|--------|--------|
| **NPU** | 2 | 608 |
| **GPU** | 3 | 1035 |
| **对比** | 2 | 404 |
| **交互** | 1 | - |
| **总计** | 8 | 2047 |

---

## 🎯 快速查找

### 我想了解...

**NPU 相关**：
- NPU 的收缩阵列如何工作？→ [NPU 架构](npu/architecture.md#3-收缩阵列)
- NPU 的 DMA 机制？→ [NPU 架构](npu/architecture.md#2-存储层次)
- 玩具 NPU 和真实昇腾的差距？→ [架构差异](npu/architecture_differences.md)

**GPU 相关**：
- GPU 的 Warp 调度机制？→ [Warp 调度](gpu/warp_scheduling.md)
- 什么是协作式调度？→ [Warp 调度](gpu/warp_scheduling.md#协作式调度)
- 双调度器如何提升性能？→ [双调度器](gpu/dual_scheduler_summary.md)

**性能对比**：
- 为什么 NPU 比 GPU 快？→ [性能对比](performance_comparison.md)
- 大矩阵性能如何？→ [性能对比](performance_comparison.md#16×16-矩阵乘法)

**指令和测试**：
- 指令格式是什么？→ [指令集](isa.md)
- 如何运行测试？→ [NPU 架构](npu/architecture.md#8-测试) / [GPU 架构](gpu/architecture.md#7-测试)

---

## 🔗 相关资源

### 源代码

```
src/main/scala/
├── ascend/          # NPU 实现
├── gpu/             # GPU 实现
├── common/          # 共享组件
└── top/             # 顶层和工具
```

### 测试

```
src/test/scala/
├── ascend/          # NPU 测试 (16 cases)
├── gpu/             # GPU 测试 (7 cases)
└── common/          # 共享组件测试 (4 cases)
```

### 图表

```
docs/
├── diagrams/        # 架构图 (SVG)
├── schematics/      # 电路图 (SVG)
└── interactive/     # 交互式架构图 (HTML)
```

---

## 🚀 快速开始

### 构建和测试

```bash
# 运行所有测试
sbt test

# NPU 测试
sbt "testOnly ascend.*"

# GPU 测试
sbt "testOnly gpu.*"

# 生成 Verilog
sbt "runMain top.Elaborate"
```

### 查看架构图

```bash
# 交互式架构图
open docs/interactive/index.html

# 静态架构图
open docs/diagrams/npu_architecture.svg
open docs/diagrams/gpu_architecture.svg
```

---

## 📝 文档维护

### 文档组织原则

1. **按架构分类**：NPU 和 GPU 文档分别组织
2. **避免重复**：相同内容只在一个文档中详细说明
3. **清晰引用**：使用链接引用其他文档
4. **分层组织**：架构文档概览，专题文档深入

### 文档更新检查清单

- [ ] 代码更新时同步更新文档
- [ ] 新增功能时更新架构文档
- [ ] 性能变化时更新性能对比
- [ ] 添加新文档时更新本索引

---

## 📧 反馈

如果发现文档问题或有改进建议，请提交 Issue 或 Pull Request。

---

## 📚 文档历史

- **2024-04**: 初始版本
- **2024-04**: 添加交互式架构图
- **2024-04**: 实现双调度器，更新 GPU 文档
- **2024-04**: 文档重组，按 NPU/GPU 分类
