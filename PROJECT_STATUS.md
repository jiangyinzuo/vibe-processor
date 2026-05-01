# 项目状态

**完成日期：** 2026-05-01  
**状态：** 100% 完成  
**测试通过率：** 100% (21/21)

---

## 核心功能

### NPU (昇腾风格)
- ✅ 8×8 收缩阵列，64 个 PE 并行计算
- ✅ 向量单元 (VECADD, RELU)
- ✅ DMA 引擎，非阻塞，支持 Overlap
- ✅ 多核并行 (2 个 AiCore)
- ✅ 四级存储层次 (L0/UB/L2/HBM)

### GPU (英伟达风格)
- ✅ SIMT 架构，32 线程 Warp
- ✅ 多 SM (2 个 SM 并行)
- ✅ 双 Warp 调度器，双发射
- ✅ 寄存器文件，共享内存

### DMA-Compute Overlap
- ✅ 非阻塞 DMA 指令 (DMA_LOAD/DMA_STORE/DMA_WAIT)
- ✅ 4 深度 DMA 队列
- ✅ UB 双端口 (Scalar 和 DMA 分离)
- ✅ L0 双缓冲
- ✅ **性能提升：1.22× 加速比，24.1% 重叠率**

---

## 性能数据

### DMA-Compute Overlap 效果

| 指标 | 顺序执行 | 流水线 Overlap | 提升 |
|------|---------|---------------|------|
| 总周期数 | 557 | 455 | **-18.3%** |
| 重叠周期 | 0 | 52 | **+52** |
| 重叠率 | 0.0% | **24.1%** | **+24.1%** |

---

## 测试状态

```
总测试数：     21 个
通过：         21 个 ✅
失败：         0 个
通过率：       100% 🎉
```

**NPU 测试：** CubeUnitTest, SystolicArrayTest, VectorUnitTest, PETest, IntegrationTest, MultiCoreTest, PerfCounterTest, OverlapBenchmark, OverlapTest, LargeMatmulTest

**GPU 测试：** WarpTest, SMTest

**基准测试：** MatmulBenchmark, MatmulDebug

---

## 文档

**核心文档：**
- README.md - 项目入口
- docs/isa.md - 指令集定义
- docs/performance_comparison.md - 性能对比

**NPU 文档：**
- docs/npu/architecture.md - NPU 架构
- docs/npu/dma_overlap.md - DMA Overlap 技术 ⭐
- docs/npu/performance_measurement.md - 性能测量
- docs/npu/pipeline_design.md - 流水线设计
- docs/npu/real_chip_pipelines.md - 真实芯片对比

**GPU 文档：**
- docs/gpu/architecture.md - GPU 架构
- docs/gpu/warp_scheduling.md - Warp 调度
- docs/gpu/dual_scheduler_summary.md - 双调度器

---

## 与真实硬件对比

### NPU (vs 昇腾 910)

| 特性 | 玩具版本 | 真实昇腾 910 | 差距 |
|------|---------|-------------|------|
| 收缩阵列 | 8×8 | 16×16+ | 4× |
| AI Core | 2 | 32 | 16× |
| 流水线级数 | 2 级 | 10-20 级 | 5-10× |
| 重叠率 | 24.1% | 80-90% | 3-4× |

### GPU (vs NVIDIA A100)

| 特性 | 玩具版本 | NVIDIA A100 | 差距 |
|------|---------|-------------|------|
| SM 数量 | 2 | 108 | 54× |
| Warp 大小 | 32 | 32 | 1× |
| 流水线级数 | 简化 | 20-30 级 | 10-15× |

---

## 技术亮点

1. **非阻塞 DMA** - DMA 指令立即返回，不阻塞后续指令
2. **队列管理** - 4 深度 FIFO 队列，支持多 DMA 并发
3. **双端口存储** - UB 分离 Scalar 和 DMA 访问
4. **双缓冲架构** - L0 双缓冲支持计算与加载重叠
5. **性能计数器** - 精确追踪 DMA、计算、重叠周期

---

## 优化方向

### 短期 (可提升 20-30%)
- 优化 buffer 切换逻辑
- 增加 DMA 队列深度
- 自动地址管理

### 中期 (可提升 50-100%)
- DMA 优先级调度
- 3-4 级流水线
- 扩展到 16×16 阵列

### 长期 (可提升 2-3×)
- 三缓冲架构
- 动态调度
- 多核协同

---

**最后更新：** 2026-05-01  
**版本：** v1.0
