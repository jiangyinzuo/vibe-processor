# HBM 真实结构与控制器职责

真实芯片访问 HBM 时，不能把 HBM 理解成一个单一、线性的“大数组”。HBM 是多层 DRAM stack，内部有多个可并行访问的层次；计算 die 侧的 HBM Controller 负责把物理地址映射到这些层次，并调度请求。

## 1. 物理层次

### 1.1 多个 HBM stack

高端 GPU/NPU 通常会挂多颗 HBM stack。每颗 stack 通过独立的 HBM PHY/Controller 接到计算 die，多个 stack 提供更宽的总线和更高的总带宽。

### 1.2 Channel / pseudo-channel

每个 HBM stack 内部包含多个 channel 或 pseudo-channel。HBM 的高带宽主要来自这些 channel 的并行访问能力。Controller 会根据地址映射策略把请求分散到不同 channel，避免所有访问集中在同一条通道。

### 1.3 Bank / bank group

每个 channel 内还有多个 bank 或 bank group。DRAM bank 是可相对独立工作的存储阵列单元。访问不同 bank 时，Controller 可以重叠部分 activate、read/write 和 precharge 开销；连续访问同一 bank 的不同行时，会出现 bank conflict，延迟上升。

### 1.4 Row buffer

DRAM 访问通常经过如下层次：

```text
channel -> bank -> row -> column
```

如果连续访问命中同一 bank 的同一 row，就是 row hit；如果访问同一 bank 的不同行，需要关闭旧 row 再打开新 row，就是 row miss 或 row conflict，代价更高。

## 2. 地址映射

真实 HBM Controller 的核心工作之一，是把物理地址拆解成 HBM 层次中的具体位置：

```text
physical_address
  -> stack_id
  -> channel_id / pseudo_channel_id
  -> bank_group
  -> bank
  -> row
  -> column
```

地址映射不是纯粹的格式转换。它会直接影响带宽利用率、bank conflict、row locality 和多核并发访问时的争用程度。

## 3. 请求调度

HBM Controller 通常需要同时处理以下目标：

- 把请求分散到不同 stack 和 channel，提高并行度。
- 把请求分散到不同 bank，减少 bank conflict。
- 尽量保持 row locality，提高 row hit 比例。
- 避免多个 SM、AiCore 或 DMA engine 长时间挤到同一 channel 或 bank。
- 处理读写切换、刷新、QoS、ECC、返回乱序和协议时序约束。

因此，真实 HBM Controller 不是简单的地址转发模块，而是片外存储系统的调度核心。它需要在带宽、延迟、公平性和协议约束之间做硬件级权衡。

## 4. 本项目的建模边界

当前项目中的 HBM 路径是教学简化模型，但已经比“单一线性数组”更接近真实 HBM：

- `HbmStackedMemory` 表示多 HBM stack 子系统，默认包含 4 个 stack。
- 每个 stack 内有一组 `HbmController + HbmModel`。`HbmController` 表示计算 die 侧的请求/响应边界，并实现简化的 channel/bank/row 解码、row hit / row miss 时序、bank busy backpressure 和小型请求队列。
- 地址低位选择 `stack_id`，去掉 stack 位后的本地地址再进入该 stack 内部的 channel/bank/row 解码。这样连续 line 地址会交织到多个 stack。
- `HbmModel` 负责每个 stack 的存储后端，当前仍由 `LatencyMem` 实现。
- 当前模型仍没有实现 pseudo-channel 细分、bank group 竞争、刷新、ECC、乱序返回或复杂 QoS。由于顶层请求接口没有 transaction id，`HbmStackedMemory` 对读响应保持全局请求顺序。

如果后续要继续提高真实性，可以把 HBM 路径扩展为：

```text
HbmStackedMemory
  ├── stack[0..S-1]
  │     ├── HbmController
  │     │     ├── address decoder
  │     │     ├── per-channel request queue
  │     │     ├── per-bank state machine
  │     │     └── row buffer state
  │     └── HbmModel
  │           └── storage
  └── ordered response return path
```

这种扩展可以让测试观察 channel conflict、bank conflict、row hit/miss、请求排队和多核访存争用，而不只是观察固定延迟。
