# GPU SFU (Special Function Unit) 实现

## 概述

SFU（Special Function Unit）是 GPU 中用于计算超越函数的专用硬件单元。本实现参考了真实 GPU（如 NVIDIA）的设计，目前支持 e^x 函数的计算。

## 设计特点

### 1. 架构设计

- **数量**: 每个 SM 有 `warpWidth` 个 SFU（当前为 4 或 8 个）
- **延迟**: 3 周期流水线
- **吞吐量**: 每周期可以处理一个 Warp 的所有 lane
- **数据格式**: Q16.16 定点数（16位整数 + 16位小数）

### 2. 实现方法

采用**查找表 + 线性插值**的方法：

1. **查找表（LUT）**
   - 存储 65 个关键点：e^-8 到 e^8，步长 0.25
   - 每个值用 Q16.16 格式存储
   - 表大小：65 × 32 bits = 260 bytes

2. **线性插值**
   - 公式：`result = v0 + (v1 - v0) × frac / 4`
   - frac 取输入的小数部分高 2 位（表示 0/4, 1/4, 2/4, 3/4）

3. **流水线**
   - Stage 1: 查表（计算索引，读取 v0 和 v1）
   - Stage 2: 线性插值
   - Stage 3: 输出

### 3. 精度

- **输入范围**: [-8, 8]
- **输出范围**: [e^-8, e^8] ≈ [0.000335, 2981]
- **精度**: 线性插值误差 < 1%

## 指令集

### EXP 指令

```
操作码: 0x8
格式: EXP Rd, Rs1
功能: Rd = e^Rs1
```

**指令编码**:
```
31-28: op (0x8)
27-24: rd
23-20: rs1
19-0:  unused
```

## 使用示例

### 汇编代码

```assembly
// 计算 e^x
LD   R0, [0]      // 加载 x (Q16.16 格式)
EXP  R1, R0       // R1 = e^R0
ST   [4], R1      // 存储结果
HALT
```

### Scala 测试代码

```scala
// 编码指令
def enc(op: Int, rd: Int, rs1: Int): Long =
  ((op & 0xF).toLong << 28) | ((rd & 0xF).toLong << 24) | 
  ((rs1 & 0xF).toLong << 20)

// 程序
val program = Seq(
  enc(0x2, rd = 0, rs1 = 0),   // LD R0, [0]
  enc(0x8, rd = 1, rs1 = 0),   // EXP R1, R0
  enc(0x3, rs1 = 0, rs2 = 1),  // ST [4], R1
  enc(0x1)                      // HALT
)
```

## 性能

### 延迟

- **SFU 延迟**: 3 周期
- **总延迟**: 取决于指令流水线
  - EXP 指令本身: 1 周期（发射）
  - SFU 计算: 3 周期
  - 写回: 1 周期
  - 总计: ~5 周期

### 吞吐量

- **理论吞吐量**: 每周期 1 个 Warp（所有 lane 并行）
- **实际吞吐量**: 受限于 Warp 调度和寄存器文件带宽

## 与真实 GPU 的对比

### NVIDIA GPU

- **SFU 数量**: 每个 SM 有 4 个 SFU
- **延迟**: 16-32 周期
- **支持的函数**: exp, log, sin, cos, sqrt, rsqrt 等
- **实现**: 查找表 + 二次插值

### 昇腾 NPU

- **Vector Unit**: 支持 EXP 指令
- **延迟**: ~10 周期
- **实现**: 分段查找表 + 线性插值
- **向量化**: 一次计算 128/256 个元素

### 玩具 GPU（本实现）

- **SFU 数量**: 每个 SM 有 4-8 个 SFU
- **延迟**: 3 周期
- **支持的函数**: exp（可扩展）
- **实现**: 查找表 + 线性插值

## 文件结构

```
src/main/scala/gpu/
├── SFU.scala                    # SFU 模块实现
├── SM_Shared.scala              # SM 中集成 SFU
├── InstructionDispatcher.scala  # 指令分发器（支持 EXP）
└── GpuParams.scala              # 添加 EXP 操作码

src/test/scala/gpu/
├── SFUTest.scala                # SFU 单元测试
├── SFUIntegrationTest.scala    # GPU 集成测试
└── SFUDebugTest.scala           # 调试测试
```

## 测试

### 单元测试

```bash
sbt "testOnly gpu.SFUTest"
```

测试内容：
- e^0 = 1
- e^1 ≈ 2.718
- e^-1 ≈ 0.368
- 无效输入时输出 0

### 集成测试

```bash
sbt "testOnly gpu.SFUIntegrationTest"
```

测试内容：
- 完整的 LD -> EXP -> ST 流程
- 多个输入值的验证

## 未来扩展

### 1. 支持更多函数

- **LOG**: log(x) - 对数函数
- **SIN/COS**: 三角函数
- **SQRT**: 平方根
- **RSQRT**: 倒数平方根（1/√x）

### 2. 提高精度

- 使用二次插值代替线性插值
- 增加查找表密度（步长从 0.25 减小到 0.125）

### 3. 优化性能

- 减少流水线级数（2 级）
- 增加 SFU 数量（每个 SM 16 个）
- 支持多个 Warp 并发

## 参考资料

- NVIDIA CUDA Programming Guide - Special Functions
- 昇腾 CANN 开发文档 - Vector Unit
- "Fast Exponential Computation on SIMD Architectures" (论文)
