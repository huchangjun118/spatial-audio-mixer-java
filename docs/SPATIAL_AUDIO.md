# 服务端空间音频混音

本服务在同一进程内对多路三维音源与多个听者进行确定性（deterministic）空间混音。
核心实现位于 `engine/SpatialMixer`，Spring 通过 `service/MixerService` 与
`service/web/MixerController` 暴露控制面。

## 1. 空间模型

世界坐标系为右手系：听者朝向 **+Z**，**+X 为右侧**，+Y 为上方。音源与听者都有
位置 `position` 与速度 `velocity`（单位：米、米/秒，速度仅用于多普勒）。

每路音源对每个听者的贡献依次经过：

1. **距离衰减 `distanceGain`**
   - `d <= referenceDistance`：增益恒为 1；
   - `referenceDistance < d < maxAudibleDistance`：
     `inv = referenceDistance / (referenceDistance + rolloffFactor * (d - ref))`，
     再乘以一条从 1 平滑到 0 的升余弦边 `0.5 + 0.5*cos(pi*t)`；
   - `d >= maxAudibleDistance`：增益恰为 0。
   升余弦边保证到达可听边界时函数值连续，移出/移入范围不会产生声场突变（增益本身
   再经逐样本一阶平滑，见 §3）。
2. **声像（pan）**：由音源相对听者的水平方向角
   `angle = atan2(src.x - lis.x, src.z - lis.z)` 归一化到 [-1,1]，
   采用等功率（equal-power）声像：
   `gL = cos(p*pi/2)`、`gR = sin(p*pi/2)`，`p=(pan+1)/2`。
   `MONO` 布局不做声像。
3. **线性增益**：`source.gain * listener.gain`。
4. **多普勒（可选）**：取相对速度在连线方向上的径向分量，接近为正：
   `radial = -dot(srcPos-lisPos, srcVel-lisVel) / d`，
   播放比 `ratio = (c + radial*source.dopplerFactor) / c`，
   并钳制在 `1 ± maxShiftRatio`。比例经逐样本平滑后驱动线性插值重采样器
   （`dsp/DopplerResampler`）：`ratio>1` 音调升高（接近），`<1` 音调降低（远离）。
5. **空间混响（可选）**：每“听者-音源”配对一个独立的 Schroeder 混响
   （4 个带阻尼低通的并联梳状滤波器 + 2 个串联全通，`dsp/ReverbTank`，
   延迟长度随采样率缩放）。湿声电平 = 距离增益 × 音源增益 ×
   `source.reverbSend × reverb.wetGain`。干声与湿声使用相同声像。

每路样本按 **音源 id 升序** 累加进 double 累加器（顺序确定、跨 JVM 一致），
最终硬限幅到 [-1, 1]（确定性安全限幅，不会溢出为 NaN/Inf）。

## 2. 参数含义

### MixerConfig（全局，不可变值对象）

| 参数 | 含义 |
|---|---|
| `sampleRate` | 渲染采样率（Hz）。改变属于结构性变更，会重置全部 DSP 状态。 |
| `blockSize` | 参数锁存网格大小（样本）。只影响参数更新生效粒度，不影响逐样本结果。 |
| `layout` | 输出布局：`MONO` / `STEREO`。改变属于结构性变更。 |
| `maxAudibleDistance` | 完全不可听距离（米，边界处增益恰为 0）。 |
| `referenceDistance` | 距离增益恒为 1 的范围上界（必须小于 maxAudibleDistance）。 |
| `rolloffFactor` | 反距离衰减指数系数，越大衰减越快。 |
| `smoothingRate` | 所有逐样本一阶平滑器的时间常数（样本数）。 |
| `degradationPolicy` | 过载降级策略，见 §5。 |
| `doppler` | `{enabled, speedOfSound(默认343 m/s), maxShiftRatio(默认0.1)}`。 |
| `reverb` | `{enabled, roomSize(梳状反馈 0~1), damping(阻尼低通 0~1), wetGain}`。 |

### SourceConfig（音源，运行期可整体替换）

`id, position, velocity, gain, reverbSend(0~1), dopplerFactor(每源强度系数)`。

### ListenerConfig（听者）

`id, position, velocity, gain`。

所有配置对象都是**不可变 record**，更新即整体替换 volatile 引用；渲染端在每个网格
块开始时取一次一致快照，因此永远不会读到“半更新”的参数，每次输出都对应某个自洽
快照。

## 3. 确定性与分块一致性（核心保证）

- 所有平滑器（增益、湿声、多普勒比例、静音淡变）都是**每个输出样本推进一步**的
  纯状态过程，不知道“块边界”的存在；
- 参数快照在**固定的采样时钟网格**（`clock / blockSize`）上锁存，而不是在调用
  边界锁存。因此在第 N 个样本生效的参数与你如何切分 `renderBlock` 调用完全无关；
- DOWNSAMPLE 降级的采样保持相位锁定到绝对样本时钟 `clock % hold`，同样与切分
  无关；
- 多源累加顺序固定（id 升序）、累加器为 double、限幅确定。

结论：**同一段输入按任意块大小连续分块渲染，与一次性渲染逐样本一致**（测试
`chunkedRenderingMatchesStereo`、`dopplerDeterminismAndBypass`、
`reverbChangesOutputButStaysChunkDeterministic`、
`overloadDownsampleChunkedMatchesOneShot` 以 `0f` 容差断言这一点）。

### 效果开关的精确旁路

- 多普勒关闭时，重采样器走 `ratio == 1.0` 直通分支，直接返回环形缓冲中的原始
  样本，**不做任何插值运算**，与从未启用多普勒的基线逐样本一致；
- 混响关闭时不构造/不调用混响 tank，湿声增益强制为 0，输出与从未启用混响的基线
  逐样本一致（测试 `disabledEffectsMatchBaselineExactly`）。

## 4. 音源生命周期与异常隔离

- **新增音源**：配对增益以当前目标增益为初值，因此“音源从时间轴一开始就在
  场景中”的基线可以逐样本直通；进入/离开可听边界的平滑由距离升余弦边与逐样本
  增益平滑保证，没有爆音。
- **移出可听范围 / 重新进入**：距离增益经升余弦边 + 逐样本平滑连续过零，无突变
  （测试 `sourceReentersAudibleRangeSmoothly`，并检查最大样本间跳变）。
- **删除音源**：该源在每个听者处进入 `dying`，干/湿增益在
  `DEATH_FADE_SAMPLES`（512 样本）内线性收到 0，之后配对状态（含混响 tank）
  被整体丢弃，输入缓冲同时移除——**不会残留混响尾或串音**
  （测试 `removedSourceLeavesNoResidual`）。同名重新加入会得到全新的缓冲与状态。
- **运行期更新参数**：不可变配置 + 网格块快照，`engine` 层无全局大锁；每个听者
  有独立锁和独立配对状态，多听者互不影响（测试
  `multipleListenersAreIndependent`、`concurrentUpdatesProduceConsistentSnapshots`
  在 4 线程下并发更新+渲染，断言所有输出有限）。
- **单听者渲染失败**：异常被该听者的渲染路径捕获，返回全静音结果与 `error`
  描述，绝不传播；其他听者正常渲染
  （测试钩子 `setFaultInjected(listenerId, …)` 与
  `listenerFailureIsIsolated`）。

## 5. 过载与降级语义

每个网格块的成本模型：

```
audibleSources = 距离 < maxAudibleDistance 且 gain>0 的音源数
cost           = audibleSources * sourceCostUnits
overloaded     = cost > capacityUnits
```

过载时依据 `DegradationPolicy.mode` 执行**确定性**降级，且在结果中通过
`degraded=true` 与 `degradationMode` 如实回报：

- **`LIMIT_SOURCES`**：只对距离最近的 `maxActiveSources` 个音源做完整 DSP
  （距离相同时按 id 决胜）；其余音源不跑 DSP，其干/湿增益平滑收到 0（不会戛然
  而止），被重新选中时再平滑恢复。降级稳定后输出与“混音器里只有这些近源”一致
  （测试 `overloadLimitSourcesIsDeterministic`）。
- **`DOWNSAMPLE`**：DSP 每 `downsampleFactor` 个样本才推进一次，中间样本
  **保持上一次结果（sample & hold）**。这是有意的音质下降，相位锁定到绝对时钟，
  分块/一次性结果仍逐样本一致（测试
  `overloadDownsampleChunkedMatchesOneShot`）。
- **`MUTE`**：完全跳过音源 DSP，输出经一个逐样本平滑器软淡变到 0（无爆音）；
  恢复时从静音淡回，重采样器重锚定到最新输入，不回放陈旧样本
  （测试 `overloadMuteProducesSilenceEventually`）。

输入侧同样不阻塞：每源一个定长环形缓冲（容量=采样率，即约 1 秒），生产快于最慢
消费者时覆盖最旧样本并计入 dropped 计数（`getDroppedSamples` / 输入接口返回值）；
读取尚未写入或已被覆盖的位置确定性地返回 0（静音）。**任何过载都不会阻塞或
崩溃。**

## 6. HTTP API（前缀 `/api/mixer`）

| 方法/路径 | 说明 |
|---|---|
| `POST /config` | 替换全局配置（字段可部分提供，未提供项沿用现值；会重建引擎）。 |
| `POST /sources` | 新增音源 `{id,position,velocity,gain,reverbSend,dopplerFactor}`。 |
| `POST /listeners` | 新增听者 `{id,position,velocity,gain}`。 |
| `DELETE /sources/{id}` | 删除音源（平滑淡出并清理混响）。 |
| `DELETE /listeners/{id}` | 删除听者（立即释放其全部渲染状态）。 |
| `POST /input` | 提交单声道样本 `{sourceId, samples:[float]}`，返回本次丢弃样本数。 |
| `POST /render?samples=N` | 为所有听者渲染 N 样本（缺省用 blockSize），返回每听者平面声道数组。 |
| `GET /health` | 存活探针。 |

典型用法是：以固定节奏 `POST /input` 推入各源采样，再以固定块大小调用
`POST /render` 拉取每个听者的输出。块大小可任意选择（不要求等于 blockSize）。

## 7. 测试覆盖

`tests/SpatialMixerTest`（16 个，纯引擎）覆盖：分块/一次性逐样本一致、移动后
声像与距离更新、多听者独立、效果开关逐样本旁路、多普勒与混响的确定性、块边界
爆音、可听范围重入、删除后无残留、同名重新加入、三种过载降级、并发参数更新、
单听者故障隔离。`tests/MixerControllerTest` 覆盖 HTTP 全生命周期与错误码。
