# 项目状态

**最后更新：** 2026-05-02  
**状态：** 100% 完成  
**测试通过率：** 100% (37/37)

---

## 核心功能

### NPU (昇腾风格)
- ✅ 8×8 收缩阵列，64 个 PE 并行计算
- ✅ 向量单元 (VECADD, RELU)
- ✅ DMA 引擎，非阻塞，支持 Overlap
- ✅ 多核并行 (4 个 AiCore)
- ✅ 四级存储层次 (L0/UB/L2/HBM)
- ✅ **DMA-Compute Overlap：1.22× 加速比，24.1% 重叠率**

### GPU (英伟达风格)
- ✅ SIMT 架构，Warp 执行模型
- ✅ 多 SM (4 个 SM 并行)
- ✅ 双 Warp 调度器，2× 性能提升
- ✅ 共享 CUDA Core 架构（符合真实 GPU 设计）
- ✅ 共享寄存器文件，全局内存访问
- ✅ **资源利用率：从 6.25% 提升到 25-100%**

---

## 性能数据

### NPU - DMA-Compute Overlap
| 指标 | 顺序执行 | 流水线 Overlap | 提升 |
|------|---------|---------------|------|
| 总周期数 | 557 | 455 | **-18.3%** |
| 重叠周期 | 0 | 52 | **+52** |
| 重叠率 | 0.0% | **24.1%** | **+24.1%** |

### GPU - 共享架构重构
| 指标 | 原始架构 | 共享架构 | 提升 |
|------|---------|---------|------|
| CUDA Core 数量 | 16 (独立) | 4 (共享) | **4× 减少** |
| 资源利用率 | 6.25% | 25-100% | **4-16× 提升** |
| SM 利用率 | N/A | 85.7% | **高效** |

---

## 测试状态

```
总测试数：     62 个
通过：         62 个 ✅
失败：         0 个
通过率：       100% 🎉
```

**NPU 测试 (28)：** IntegrationTest, CubeCoreTest, PerfCounterTest, OverlapBenchmark, CubeUnitTest, SystolicArrayTest, VectorUnitTest, MultiCoreTest, OverlapTest, LargeMatmulTest 等

**GPU/通用/Benchmark 测试 (34)：** GpuIntegrationTest, DualSchedulerTest, SharedArchDebug, InstructionDispatcherMultiIssueTest, QuickSharedArchTest, CudaCoreTest, LatencyMemTest, MatmulBenchmark 等

---

## 文档

**核心文档：**
- README.md - 项目入口
- docs/isa.md - 指令集定义
- docs/performance_comparison.md - 性能对比

**NPU 文档：**
- docs/npu/architecture.md - NPU 架构
- docs/npu/dma_overlap.md - DMA Overlap 技术
- docs/npu/performance_measurement.md - 性能测量

**GPU 文档：**
- docs/gpu/architecture.md - GPU 架构
- docs/gpu/architecture_comparison.md - 玩具 vs 真实 GPU 对比
- docs/gpu/shared_architecture_summary.md - 共享架构重构总结
- docs/gpu/dual_scheduler_summary.md - 双调度器
- docs/gpu/warp_scheduling.md - Warp 调度

---

## 技术亮点

### NPU
1. **非阻塞 DMA** - DMA 指令立即返回，不阻塞后续指令
2. **队列管理** - 4 深度 FIFO 队列，支持多 DMA 并发
3. **双端口存储** - UB 分离 Scalar 和 DMA 访问
4. **双缓冲架构** - L0 双缓冲支持计算与加载重叠

### GPU
1. **共享 CUDA Core** - SM 级别共享资源，符合真实 GPU 架构
2. **轻量级 Warp** - 只保存执行上下文，不包含物理计算单元
3. **内存数据缓冲** - 解决连续 LOAD 指令互相覆盖问题
4. **写端口冲突处理** - 内存写回与算术写回互斥

---

## 与真实硬件对比

### NPU (vs 昇腾 910)
| 特性 | 玩具版本 | 真实昇腾 910 | 差距 |
|------|---------|-------------|------|
| 收缩阵列 | 8×8 | 16×16+ | 4× |
| AI Core | 4 | 32 | 8× |
| 流水线级数 | 2 级 | 10-20 级 | 5-10× |
| 重叠率 | 24.1% | 80-90% | 3-4× |

### GPU (vs NVIDIA A100)
| 特性 | 玩具版本 | NVIDIA A100 | 差距 |
|------|---------|-------------|------|
| SM 数量 | 4 | 108 | 27× |
| Warp 大小 | 4 | 32 | 8× |
| 调度器/SM | 2 | 4 | 2× |
| 架构模型 | ✅ 共享 CUDA Core | ✅ 共享 CUDA Core | **一致** |
