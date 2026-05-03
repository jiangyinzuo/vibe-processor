# NPU vs GPU 矩阵乘法性能对比分析（更新版）

## 测试场景

### 小矩阵：4×4 矩阵乘法
**测试数据**：
```
A = [[1, 2, 3, 4],      W = [[1, 0, 2, 1],
     [5, 6, 7, 8],           [3, 1, 0, 2],
     [2, 3, 1, 4],           [2, 4, 1, 3],
     [7, 1, 5, 3]]           [0, 2, 3, 1]]

C = A × W = [[17, 18, 15, 18],
             [45, 42, 35, 46],
             [15, 15, 11, 16],
             [24, 29, 24, 28]]
```

### 大矩阵：16×16 矩阵乘法（Tiling 分析）
- 使用 4×4 SystolicArray 分块计算
- 16×16 矩阵分解为 4×4 = 16 个 4×4 块
- 每个输出块需要 4 次 MATMUL + 3 次 VECADD

---

## NPU (昇腾) 性能分析

### 架构特点
- **计算单元**：4×4 SystolicArray (16 个 PE)
- **数据流**：Weight-Stationary（权重固定，激活流动）
- **内存层次**：HBM → L2 → UB → L0 (actBuf/weightBuf)
- **数据搬运**：显式 DMA 指令

### 4×4 矩阵性能数据（单核）

| 指标 | 数值 | 说明 |
|------|------|------|
| **总周期数** | 107 | start → halted 总耗时 |
| **Cube 计算周期** | 16 | SystolicArray 有效计算时间 |
| **DMA 周期** | 36 | 数据搬运耗时 (2×LOAD + 1×STORE) |
| **气泡周期** | 55 | Scalar 等待 Cube/DMA 的空闲周期 |
| **计算效率** | 15.0% | cubeCompute / total |
| **DMA 占比** | 33.6% | dmaCycles / total |
| **理论峰值利用率** | 22.5% | cubeCompute / (cubeCompute + bubble) |

**性能瓶颈**：
- ❌ DMA 开销大（33.6%）
- ❌ 气泡周期多（51.4%）
- ❌ 小矩阵无法充分利用 16 个 PE

### 16×16 矩阵性能估算（Tiling）

**计算分解**：
- 16×16 矩阵 = 4×4 个 4×4 块
- 每个输出块 C[i][j] = Σ(k=0..3) A[i][k] × W[k][j]
- 需要 4 次 MATMUL + 3 次 VECADD（累加中间结果）

**周期估算**：
```
每个输出块耗时：
  - 4 × MATMUL:  4 × 107 = 428 周期
  - 3 × VECADD:  3 × 50  = 150 周期（估算）
  - 小计：578 周期

总周期数：16 个输出块 × 578 = 9,248 周期
```

**理论峰值分析**：
```
总计算量：16×16×16 = 4,096 次乘加操作
SystolicArray 吞吐量：16 次乘加/周期
理论最少周期：4,096 / 16 = 256 周期
实际周期：9,248 周期
硬件利用率：256 / 9,248 = 2.8%
```

**性能瓶颈**：
- ❌ **Tiling 开销巨大**：需要 112 次操作（16×7），每次都有 DMA 和控制开销
- ❌ **DMA 成为瓶颈**：每次 MATMUL 需要 2×DMA_LOAD + 1×DMA_STORE
- ❌ **中间结果累加**：需要额外的 VECADD 操作和内存访问
- ❌ **小 SystolicArray**：4×4 PE 对于 16×16 矩阵来说太小

### 多核扩展（2 核数据并行）

**2 核性能**：
- 每个核处理 8 个输出块
- 总周期数：9,248 周期（并行执行）
- 吞吐量：2× 单核
- 效率：接近线性扩展

---

## GPU (英伟达) 性能分析

### 架构特点
- **计算单元**：SIMT 标量 ALU (每 Warp 4 个 CudaCore)
- **调度策略**：Round-Robin Warp Scheduler
- **内存层次**：GlobalMem → SharedMem → RegFile
- **延迟隐藏**：Warp 切换隐藏内存访问延迟

### 向量加法性能数据（4 SM，latency=1）

| 指标 | 数值 | 说明 |
|------|------|------|
| **总周期数** | 39 | 单 SM 总耗时 |
| **Live Warp 周期** | 119 | Ready + Stalled warp-cycle |
| **Eligible Warp 周期** | 103 | 可被调度器选择的 Ready warp-cycle |
| **Stalled Warp 周期** | 16 | 等待 GlobalMem 的 warp-cycle |
| **No-eligible 周期** | 0 | 没有 Ready warp 的周期 |
| **GlobalMem 请求数** | 12 | 每 SM：4 Warps × (2 LD + 1 ST) |
| **Warp 占用率** | 76.3% | liveWarpCycles / (total × 4) |

### 向量加法性能数据（4 SM，latency=10）

| 指标 | 数值 | 说明 |
|------|------|------|
| **总周期数** | 50 | 内存延迟增加，但被部分隐藏 |
| **Live Warp 周期** | 190 | Ready + Stalled warp-cycle |
| **Eligible Warp 周期** | 102 | 仍有大量周期可调度其它 warp |
| **Stalled Warp 周期** | 88 | 等待 GlobalMem 的 warp-cycle 明显增加 |
| **No-eligible 周期** | 14 | 延迟真正暴露到前端的周期 |
| **GlobalMem 请求数** | 12 | 请求数量不变，差异来自 latency |
| **Warp 占用率** | 95.0% | liveWarpCycles / (total × 4) |

解释：`latency=10` 下有 88 个 stalled warp-cycle，但只有 14 个 no-eligible cycles。也就是说，大部分等待发生时，SM 里仍有其它 Ready warp 可供调度；这就是通过 warp 调度隐藏访存延迟。

### 16×16 矩阵乘法性能估算

**朴素实现**（每线程计算一个元素）：
```
每个元素需要：
  - 16 次 LD（读取 A 的一行）
  - 16 次 LD（读取 W 的一列）
  - 16 次 MAD（乘加累加）
  - 1 次 ST（写回结果）

假设 latency=10：
  - 内存访问：32 × 10 = 320 周期（等待）
  - 计算：16 周期
  - 写回：10 周期
  - 每个元素：~350 周期

总周期数（单 Warp 顺序执行）：
  - 256 个元素 × 350 = 89,600 周期

多 Warp 并行（4 Warp）：
  - 理想情况：89,600 / 4 = 22,400 周期
  - 实际（考虑调度开销）：~25,000 周期
```

**优化实现**（使用 SharedMem）：
```
分块策略：
  - 将 A 和 W 分块加载到 SharedMem
  - 减少 GlobalMem 访问次数
  - 预期性能提升：2-3×

优化后周期数：~8,000 - 12,000 周期
```

---

## 性能对比总结

### 4×4 矩阵乘法

| 指标 | NPU (昇腾) | GPU (英伟达) | NPU 优势 |
|------|-----------|-------------|---------|
| **总周期数** | 107 | ~350 (估算) | **3.3× 更快** |
| **计算效率** | 15.0% | ~5% (估算) | **3× 更高** |
| **并行度** | 16 PE 同时计算 | 4 线程/Warp | **4× 更高** |
| **编程复杂度** | 显式 DMA | 嵌套循环 | GPU 更简单 |

### 16×16 矩阵乘法

| 指标 | NPU (昇腾) | GPU (英伟达) | 对比 |
|------|-----------|-------------|------|
| **总周期数** | 9,248 | ~25,000 (朴素) | **NPU 2.7× 更快** |
| **总周期数** | 9,248 | ~10,000 (优化) | **接近** |
| **硬件利用率** | 2.8% | ~10% (优化) | GPU 更高 |
| **可扩展性** | 2 核 → 2× 吞吐 | 4 SM → 4× 吞吐 | 都支持 |

### 关键洞察

#### 1. 矩阵规模的影响

**小矩阵（4×4）**：
- NPU 优势明显（3.3× 更快）
- 专用硬件效率高
- GPU 内存延迟成为瓶颈

**中等矩阵（16×16）**：
- NPU 受 Tiling 开销影响，优势减弱
- GPU 通过 SharedMem 优化可以接近 NPU
- 两者性能接近

**大矩阵（128×128+）**：
- NPU 需要更大的 SystolicArray（如 16×16 或 32×32）才能发挥优势
- GPU 通过 Tiling + SharedMem 可以高效处理
- 内存带宽成为共同瓶颈

#### 2. Tiling 开销分析

**NPU 的 Tiling 问题**：
```
4×4 SystolicArray 处理 16×16 矩阵：
  - 需要 16 个输出块
  - 每个块需要 4 次 MATMUL + 3 次 VECADD
  - 总共 112 次操作
  - 每次操作都有 DMA 和控制开销
  - 硬件利用率仅 2.8%
```

**解决方案**：
- ✅ 增大 SystolicArray 尺寸（16×16 → 一次计算整个矩阵）
- ✅ 增大片上缓存（减少 DMA 次数）
- ✅ 流水线优化（DMA 和计算重叠）

#### 3. 真实硬件对比

**真实昇腾 NPU**：
- SystolicArray 尺寸：16×16 或更大
- 多级缓存：L0A/L0B/L0C + L1 + L2
- MTE (Memory Transfer Engine) 支持流水线
- 实际性能：远超本项目的玩具实现

**真实英伟达 GPU**：
- Tensor Core：专用矩阵乘法单元（类似 SystolicArray）
- 更多 SM 和 Warp（如 80 SM × 32 Warp）
- 更大的 SharedMem 和 L2 Cache
- 实际性能：与 NPU 在同一量级

---

## 优化建议

### NPU 优化方向

1. **增大 SystolicArray**
   - 从 4×4 扩展到 16×16 或 32×32
   - 减少 Tiling 次数，提高硬件利用率

2. **优化 DMA 调度**
   - 流水线：DMA 和计算重叠
   - 双缓冲：一个缓冲区计算，另一个加载数据

3. **多核并行**
   - 2 核数据并行，吞吐量翻倍
   - 适合批量矩阵乘法（Transformer Attention）

4. **编译器优化**
   - 自动 Tiling 和数据编排
   - 减少程序员负担

### GPU 优化方向

1. **使用 SharedMem**
   - 分块加载数据到 SharedMem
   - 减少 GlobalMem 访问次数

2. **增加 Warp 数量**
   - 提高延迟隐藏能力
   - 更好地利用内存带宽

3. **使用 Tensor Core**
   - 专用矩阵乘法单元
   - 性能提升 10-20×

4. **优化内存访问模式**
   - Coalesced access（合并访问）
   - Bank conflict 避免

---

## 结论

### 小矩阵（4×4）
- ✅ **NPU 明显更快**（3.3×）
- 专用硬件优势明显
- 适合边缘设备推理

### 中等矩阵（16×16）
- ⚖️ **性能接近**
- NPU 受 Tiling 开销影响
- GPU 通过 SharedMem 优化可以追上

### 大矩阵（128×128+）
- ⚖️ **取决于硬件规模**
- NPU 需要更大的 SystolicArray
- GPU 需要 Tensor Core
- 两者在真实硬件上性能相当

### 实际应用建议

**选择 NPU**：
- 深度学习训练/推理（矩阵乘法密集）
- 固定模型部署（如 ResNet、BERT）
- 追求能效比（专用硬件功耗低）

**选择 GPU**：
- 通用计算（图形渲染、科学计算）
- 快速原型开发（编程灵活）
- 需要支持多种算法

---

## 运行测试

```bash
# NPU 4×4 矩阵乘法
sbt "testOnly ascend.IntegrationTest"

# NPU 大矩阵性能估算
sbt "testOnly ascend.LargeMatmulTest"

# GPU 向量加法
sbt "testOnly gpu.GpuIntegrationTest"

# 多核性能测试
sbt "testOnly ascend.MultiCoreTest"
```

---

## 参考资料

- NPU 架构：`docs/architecture_zh.md` - 第一章
- GPU 架构：`docs/architecture_zh.md` - 第二章
- 性能计数器：`src/main/scala/ascend/PerfCounters.scala`
- 测试用例：`src/test/scala/ascend/IntegrationTest.scala`
- 大矩阵测试：`src/test/scala/ascend/LargeMatmulTest.scala`
