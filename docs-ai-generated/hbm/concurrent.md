芯片访问 HBM，可以把它想成：

> **计算单元发出 load/store 请求 → 片上互连/NoC → L2/cache/memory partition → HBM controller/PHY → HBM stack 内部 bank/row/column → 数据返回**

它不是“一个大内存口”被一个核心独占访问，而是**大量计算单元同时把请求打散到很多 memory partition、HBM stack、channel、bank 上**。并行性主要就体现在这些层次里。

---

## 1. 从计算单元看：大量线程/warp/CTA 同时发请求

以 NVIDIA GPU 为例：

```text
SM / CUDA Core
   ↓
warp 发起 global load/store
   ↓
L1 / shared memory / register file
   ↓
L2 cache
   ↓
memory partition
   ↓
HBM controller
   ↓
HBM stack
```

每个 SM 里有大量 warp。一个 warp 执行到 `global memory load` 时，硬件会把 32 个线程的地址合并，也就是 **coalescing**：

```text
线程0 读 A[0]
线程1 读 A[1]
线程2 读 A[2]
...
线程31 读 A[31]
```

如果这些地址连续，硬件可以合并成少数几个大内存事务，比如 32B/64B/128B transaction。

如果地址乱飞：

```text
线程0 读 A[100]
线程1 读 A[99821]
线程2 读 A[7]
线程3 读 A[123456]
...
```

那就会拆成很多内存事务，HBM 带宽利用率会变差。
所以第一个并行点是：

> **成千上万个线程/warp 同时制造内存请求，硬件靠高并发隐藏 HBM 延迟。**

这和 CPU 很不一样。CPU 更依赖 cache 命中、分支预测、乱序执行；GPU/NPU 更倾向于“我有海量请求，谁先回来谁先用”。

---

## 2. 从地址映射看：地址会被打散到多个 HBM stack / channel / bank

HBM 不是一整块线性硬件。比如一个 4-stack HBM 设计，可以粗略想成：

```text
HBM Stack 0
HBM Stack 1
HBM Stack 2
HBM Stack 3
```

每个 stack 又有多个 channel：

```text
HBM Stack 0:
  Channel 0
  Channel 1
  ...
  Channel 7    # HBM2/HBM2e 常见口径

HBM Stack 1:
  Channel 0
  Channel 1
  ...
  Channel 7
```

每个 channel 下面还有 bank / bank group：

```text
Channel
  ├── Bank 0
  ├── Bank 1
  ├── Bank 2
  └── ...
```

芯片会用物理地址的若干 bit 做映射，把连续或近似连续的访问**交织**到多个 stack/channel/bank：

```text
地址 A0  → Stack 0, Channel 0, Bank 3
地址 A1  → Stack 1, Channel 2, Bank 7
地址 A2  → Stack 2, Channel 1, Bank 0
地址 A3  → Stack 3, Channel 5, Bank 2
...
```

真实映射会更复杂，通常有 xor/hash/interleave，目的是避免某些地址模式把所有请求都打到同一个 HBM channel 上。

第二个并行点就是：

> **不同地址可以并行落到不同 stack、不同 channel、不同 bank 上。**

如果访存模式好，多个 HBM stack 会一起忙；如果访存模式坏，可能只有某几个 channel 很忙，其他 channel 在摸鱼。这个就叫 **partition camping / channel hot spot**，很讨厌，性能像在高速路上只开了一个收费口。

---

## 3. 从 memory controller 看：每个分区可以独立排队、调度、重排

HBM controller 不是被动转发。它会维护很多请求队列：

```text
Read Queue
Write Queue
Pending Queue
Bank State Table
Refresh / timing constraints
```

它要做几件事：

1. 判断某个 bank 当前 row 是否已经打开。
2. 如果 row 命中，就直接发 column read/write。
3. 如果 row 不命中，要 precharge 旧 row，再 activate 新 row。
4. 在满足 HBM 时序约束的前提下，尽量调度更多请求。
5. 在 read/write 之间切换，避免频繁 turn-around。

一个 HBM 访问不是“给地址，马上出数据”，而是类似：

```text
ACTIVATE row
等待 tRCD
READ column
等待 CAS latency
数据 burst 返回
```

如果下一个请求命中同一个打开的 row，就更快：

```text
READ column
READ column
READ column
...
```

如果频繁换 row：

```text
PRECHARGE
ACTIVATE
READ
PRECHARGE
ACTIVATE
READ
...
```

效率会差很多。

第三个并行点是：

> **多个 memory controller / partition 独立维护队列，并对 HBM bank 进行并行调度。**

这和 SSD 控制器有点像：不是一个请求做完再做下一个，而是要同时喂满多个通道、多个 die、多个 plane。HBM 也是类似思想，只是延迟/带宽/时序尺度完全不同。

---

## 4. 从 HBM stack 内部看：bank-level parallelism

每个 HBM channel 下面有多个 bank。不同 bank 可以并行处理不同 row 的访问。

粗略画一下：

```text
HBM Channel 0
  ├── Bank 0: 正在读 row 10
  ├── Bank 1: 正在激活 row 88
  ├── Bank 2: 正在等待数据返回
  ├── Bank 3: 空闲
  └── ...
```

如果你的访问能分散到不同 bank，那么 memory controller 可以流水化调度：

```text
cycle 0:  Bank0 ACT
cycle 2:  Bank1 ACT
cycle 4:  Bank2 ACT
cycle 8:  Bank0 READ
cycle 10: Bank1 READ
cycle 12: Bank2 READ
```

这样总线上持续有数据流动。

但如果访问都打到同一个 bank，并且 row 还不断切换，那就惨了：

```text
Bank0:
  open row 1
  read
  close row 1
  open row 999
  read
  close row 999
  open row 5
  read
```

这个时候你虽然有很多 HBM channel，但实际利用率可能不高。

第四个并行点：

> **HBM 内部多个 bank 可以并行工作，靠 bank-level parallelism 提高有效带宽。**

---

## 5. 从数据返回看：burst 传输 + 超宽 IO

HBM 的核心优势是“宽”。

比如一个 HBM stack 典型是 **1024-bit 数据总线**。
这不是说一次只读 1 个 int，而是一次 burst 可以搬一大坨数据。

可以粗略理解成：

```text
传统 GDDR：频率高，线比较少
HBM：频率没那么夸张，但线非常宽，stack 离芯片很近
```

HBM 放在 interposer 上，和 GPU/NPU die 的距离非常短，可以铺非常多 IO 线：

```text
GPU/NPU die  ───── silicon interposer ───── HBM stack
```

所以 HBM 的特点是：

```text
带宽高
功耗/bit 低
容量贵
封装复杂
延迟不一定比 DDR 神奇很多
```

第五个并行点：

> **HBM 靠超宽 IO + burst 传输，把每次事务的数据量做大。**

---

## 6. GPU 访问 HBM 时的并行性总结

可以从上到下看：

```text
线程级并行：
  很多 SM / warp 同时发起访存

事务级并行：
  一个 warp 的多个线程地址被 coalesce 成多个 memory transaction

cache/partition 级并行：
  请求分布到多个 L2 slice / memory partition

HBM stack 级并行：
  多个 HBM stack 同时工作

channel 级并行：
  每个 stack 内多个 channel 并行

bank 级并行：
  每个 channel 内多个 bank 交错服务请求

burst/总线级并行：
  每次传输用超宽位宽搬运大量数据
```

画成图就是：

```text
Thousands of threads
        ↓
Many SMs / AI Cores
        ↓
L1 / Local buffer
        ↓
L2 / Unified cache
        ↓
NoC / Crossbar
        ↓
Memory partitions
   ↓      ↓      ↓      ↓
HBM0    HBM1    HBM2    HBM3
 ↓       ↓       ↓       ↓
channels / banks / rows / columns
```

这就是 HBM 访问并行性的真正来源。

---

## 7. NPU 和 GPU 的差异：NPU 更强调显式搬运和片上缓冲复用

GPU 通常是：

```text
线程执行 load/store
硬件 coalescing
cache hierarchy
memory controller 调度 HBM
```

NPU/AI Core 往往更像：

```text
HBM / Global Memory
      ↓
DMA / Data Move Engine
      ↓
片上 SRAM / L1 / Unified Buffer
      ↓
Matrix / Cube / Vector Unit
```

例如很多 NPU 会强调：

```text
GM  →  L2  →  L1/UB  →  Compute
```

或者：

```text
Global Memory
  ↓ DMA
Unified Buffer
  ↓
Cube Unit / Vector Unit
```

这类架构里，程序员或编译器通常更关心：

1. tensor 怎么切 tile；
2. tile 怎么从 HBM 搬到片上 buffer；
3. compute 和 data movement 怎么 overlap；
4. 片上 buffer 能不能复用；
5. 多个搬运单元能不能并行工作；
6. 多个 AI Core 之间怎么分配数据。

所以 NPU 的并行性除了 HBM 自身之外，还有一个非常重要的层次：

> **搬运单元和计算单元之间的流水并行。**

典型模式：

```text
时间片 0:
  DMA 搬 tile 0 到片上 buffer

时间片 1:
  Compute 计算 tile 0
  DMA 搬 tile 1

时间片 2:
  Compute 计算 tile 1
  DMA 搬 tile 2

时间片 3:
  Compute 计算 tile 2
  DMA 搬 tile 3
```

这就是 double buffering / ping-pong buffer：

```text
Buffer A: 正在被 compute 使用
Buffer B: 正在从 HBM 加载下一块数据

下一轮交换：

Buffer A: 加载下一块
Buffer B: 被 compute 使用
```

这个在 GPU 上也存在，比如 shared memory + `cp.async`，但 NPU/AI Core 的编程模型往往更显式。

---

## 8. 举一个矩阵乘法例子

假设做：

```text
C = A × B
```

天真的访问方式：

```text
每次计算 C[i][j] 都反复从 HBM 读 A 和 B
```

这会疯狂浪费 HBM 带宽。

高性能实现会 tile：

```text
A tile 从 HBM 搬到片上 buffer
B tile 从 HBM 搬到片上 buffer
在片上反复复用 A tile / B tile
算出 C tile
最后把 C tile 写回 HBM
```

流程：

```text
HBM:
  A, B, C

片上 SRAM:
  A_tile
  B_tile
  C_tile accumulator

计算单元:
  对 A_tile × B_tile 做大量 MAC
```

并行性体现在：

```text
多个 Core 负责不同 C tile
每个 Core 内部矩阵单元并行 MAC
DMA 同时预取下一个 A/B tile
HBM 多 stack/channel 并行提供数据
计算和搬运 overlap
```

如果 tile 设计好，HBM 只负责“喂数据”，大部分计算都在片上完成。
如果 tile 设计差，计算单元就会等 HBM，性能崩。

---

## 9. 对大模型推理来说，HBM 并行性在哪里最关键？

大模型推理里，常见瓶颈有几个。

### 权重读取

GEMM/GEMV 要从 HBM 读大量权重：

```text
W 从 HBM 读入
x 从 HBM/Cache/片上 buffer 读入
输出 y 写回
```

batch 小的时候，权重复用低，容易 memory-bound。尤其 decode 阶段：

```text
batch 小
每 token 都要扫大量权重
矩阵变成 GEMV 或 skinny GEMM
```

这个时候 HBM 带宽非常关键。

### KV Cache 读取

Attention decode 阶段要读历史 KV：

```text
Q 当前 token
K/V 历史 tokens
```

如果上下文很长，KV cache 访问量很大：

```text
每生成一个 token，都要扫一遍历史 K/V
```

这也是 HBM 带宽大户。

### MoE

MoE 会访问不同 expert 的权重，访问模式更不规则：

```text
token 0 → expert 3
token 1 → expert 17
token 2 → expert 3
token 3 → expert 42
```

如果调度不好，会出现：

```text
某些 expert 热
某些 HBM partition 热
通信/访存碎片多
```

所以 MoE 更依赖好的 batching、routing、expert placement 和内存布局。

---

## 10. 为什么“通道数多”不一定等于性能一定高？

因为 HBM 峰值带宽需要一堆条件：

```text
访问足够并行
地址分布均匀
访问粒度够大
coalescing 好
bank conflict 少
row locality 合理
cache/L2 命中合理
计算和搬运能 overlap
```

反例：

```text
所有线程都随机读小对象
地址不连续
每个 transaction 只用几个字节
反复打到同一个 partition
```

这种情况下，哪怕 HBM 有很多 channel，实际带宽也可能很低。

所以硬件峰值是：

```text
理论上限
```

实际程序看到的是：

```text
有效带宽 = 峰值带宽 × 利用率
```

很多优化，本质上都是提高这个利用率。

---

## 11. 一句话总结

**芯片访问 HBM 是一个多级并行流水：计算单元并发发请求，地址被打散到多个 L2/memory partition/HBM stack/channel/bank，memory controller 负责调度，HBM 用超宽 IO 和 burst 返回数据。**

GPU 更像：

```text
海量线程 + cache + memory controller 自动调度
```

NPU 更像：

```text
显式/半显式数据搬运 + 片上 buffer tile 复用 + 计算/搬运流水化
```

所以 HBM 的并行性不只是“有多少个 channel”，而是：

```text
线程并行
请求并行
partition 并行
stack 并行
channel 并行
bank 并行
burst 并行
计算-搬运流水并行
```

真正的性能优化，就是让这些层次尽量同时满载。

