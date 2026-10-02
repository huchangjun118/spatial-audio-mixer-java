# 服务端空间音频混音引擎

一套基于 Java 17 + Spring Boot 的服务端空间音频混音能力。多路音源与听者处于三维空间中，混音输出体现方位、距离与运动带来的听感变化。

**核心特性**

- 多路音源 / 多个听者同时存在，每路音源的方位、距离衰减与增益都在输出中体现
- 音源或听者移动后下一帧声像随之更新，不同听者输出互不影响
- 多普勒频移与空间混响，可单独开关、参数可配置；关闭时与基线逐样本一致
- 按采样块渲染，块大小 / 采样率 / 声道布局可配置；分块渲染与一次性渲染逐样本一致
- 运行期增删音源与调整参数，写时复制快照保证并发更新不产生半更新状态
- 过载降级（限幅 / 降采样 / 静音），行为确定且可解释
- 音源移出可听范围平滑过渡，移除的音源不残留混响或串音
- 单个听者渲染失败不影响其他听者

---

## 1. 空间模型

### 1.1 坐标系

左手坐标系：`x` 向右、`y` 向上、`z` 向前（听者面向 `+z`）。方位角 `azimuth` 定义在水平面内，`0` 表示正前方，正值表示右侧，负值表示左侧。

听者由 `position`（位置）、`forward`（朝向）、`up`（头顶方向）三个向量描述。右方向通过 `up × forward` 归一化得到。

### 1.2 方位声像（VBAP）

采用二维向量基幅度定位（Vector-Base Amplitude Panning）。对每个声道布局，预先定义各扬声器在水平面的单位向量；给定音源方位角后，找到包围该方向的相邻扬声器对，求解使扬声器向量加权和等于音源方向的增益，并用等功率归一化（`Σg² = 1`）。

- 单声道（MONO）：全部信号归中。
- 立体声（STEREO）：左右扬声器对。
- 四声道（QUAD）：前后左右两对。
- 5.1 / 7.1：多对扬声器分段 VBAP；LFE（低音声道）无方向性，取单声道信号的一半馈入。

仰角被忽略（仅水平面定位）。

### 1.3 距离衰减

音源到听者的距离为 `d`，衰减系数：

```
att(d) = refDistance / (refDistance + rolloff * max(0, d - refDistance))
```

- `d ≤ refDistance`：`att = 1`（参考距离内不衰减）。
- `d > refDistance`：按距离滚降，`rolloff` 越大衰减越快。
- 接近 `maxDistance` 时，在 `[maxDistance - fadeMargin, maxDistance]` 区间内用 smoothstep（`t²(3-2t)`）平滑过渡到 0，`fadeMargin = max(1, 0.05 * maxDistance)`。`d ≥ maxDistance` 时输出为 0。

距离衰减与方位声像增益逐样本平滑（时间常数 10 ms），避免移动或块边界产生爆音。

### 1.4 多普勒频移

多普勒通过可变延迟线实现。延迟线长度对应声音传播时间：

```
delay(d) = d / speedOfSound * sampleRate
```

音源与听者的相对径向速度决定延迟的变化率，从而改变读出速率，产生频移。延迟逐样本平滑（时间常数 30 ms），平滑系数由采样率推导，与块大小无关。

- 音源靠近：距离减小 → 延迟减小 → 读出速率 > 1 → 音调升高。
- 音源远离：距离增大 → 延迟增大 → 读出速率 < 1 → 音调降低。

读出速率被限制在 `[0.1, 2.0]`，延迟线读头不允许回退到未写入区域，避免非物理快速移动产生静音或爆音。

关闭多普勒时，信号直接通过，不经过延迟线，与无效果基线完全一致。

### 1.5 空间混响

采用 Schroeder/Moorer 混响：4 个并联梳状滤波器（带低通反馈阻尼）+ 2 个串联全通滤波器。每个音源拥有独立的混响实例（`Reverb`），因此：

- 移除音源时丢弃其混响状态 → 不残留混响尾巴。
- 不同音源的混响互不串音。

混响输入为 `音源信号 * 距离衰减 * 音源增益 * 混响发送量(reverbSend)`，输出按 `wetLevel` 缩放后叠加到各声道。混响参数：

- `roomSize`：0..1，控制反馈量（混响衰减时间）。
- `damping`：0..1，高频阻尼。
- `wetLevel`：0..1，湿信号输出电平。

关闭混响时，湿路径完全跳过，与无效果基线逐样本一致。

---

## 2. 参数说明

### 2.1 音源参数（SourceConfig）

| 参数 | 含义 | 默认值 |
|------|------|--------|
| `position` | 三维位置 (x, y, z) | (0,0,0) |
| `gain` | 线性增益（0..1+） | 1.0 |
| `refDistance` | 参考距离，小于此距离不衰减 | 1.0 m |
| `maxDistance` | 最大可听距离，达到时静音 | 100.0 m |
| `rolloff` | 衰减滚降系数，越大衰减越快 | 1.0 |
| `reverbSend` | 混入混响的发送量（0..1） | 0.2 |

### 2.2 听者参数（ListenerConfig）

| 参数 | 含义 | 默认值 |
|------|------|--------|
| `position` | 三维位置 | (0,0,0) |
| `forward` | 朝向向量（非零） | (0,0,1) |
| `up` | 头顶方向向量（非零） | (0,1,0) |
| `gain` | 主输出增益 | 1.0 |

### 2.3 混音器参数（MixerConfig）

| 参数 | 含义 | 默认值 |
|------|------|--------|
| `format.sampleRate` | 采样率 | 44100 Hz |
| `format.blockSize` | 每渲染块帧数 | 256 |
| `format.channelLayout` | 声道布局 | STEREO |
| `doppler.enabled` | 多普勒开关 | true |
| `doppler.speedOfSound` | 声速 (m/s) | 343 |
| `reverb.enabled` | 混响开关 | true |
| `reverb.roomSize` | 混响房间大小 | 0.8 |
| `reverb.damping` | 高频阻尼 | 0.5 |
| `reverb.wetLevel` | 湿信号电平 | 0.3 |
| `degradation.enabled` | 降级开关 | true |
| `degradation.budgetFactor` | 实时预算占比 | 0.8 |
| `degradation.escalateRatio` | 升级负载比阈值 | 1.2 |
| `degradation.recoverRatio` | 恢复负载比阈值 | 0.7 |
| `degradation.escalateBlocks` | 连续超预算块数 | 3 |
| `degradation.recoverBlocks` | 连续低负载块数 | 10 |
| `degradation.forcedLevel` | 强制降级级别（测试用） | null |

---

## 3. 降级语义

降级控制器（`DegradationController`）逐块测量渲染墙钟时间，与实时预算（`blockSize / sampleRate * budgetFactor`）比较。

### 3.1 降级级别

| 级别 | 行为 | 音质影响 |
|------|------|----------|
| `NORMAL` | 全部效果全采样处理 | 无 |
| `REVERB_OFF` | 跳过混响湿路径 | 混响消失 |
| `HALF_RATE` | 半采样率渲染（每 2 帧处理 1 帧，采样保持） | 高频损失 |
| `MUTED` | 输出静音 | 无声 |

### 3.2 状态机

- 连续 `escalateBlocks` 个块负载 ≥ `escalateRatio` → 升级一级。
- 连续 `recoverBlocks` 个块负载 ≤ `recoverRatio` → 恢复一级。
- 进入 `MUTED` 后有冷却时间（`recoverBlocks * 4` 个块），防止静音/取消静音快速振荡。
- 升级路径：`NORMAL → REVERB_OFF → HALF_RATE → MUTED`；恢复路径相反。

### 3.3 确定性

- 信号路径无随机数、无墙钟依赖，同一段输入在任意时刻渲染结果一致。
- 自动降级级别的选择依赖墙钟时间（跨机器不确定），但**在任意固定级别下的输出是确定的**。
- 可通过 `degradation.forcedLevel` 强制指定级别（测试用），此时行为完全确定。
- 输出端始终应用硬限幅（钳位到 `[-1, 1]`），防止多路叠加溢出。

---

## 4. 并发与快照一致性

- 所有管理操作（增删音源 / 听者）采用**写时复制**：每次发布一个新的不可变 `Snapshot`。
- 渲染调用在整个块期间使用同一个快照，参数更新永远不会在渲染中途被观察到。
- 每次输出都对应某个自洽的参数快照。
- 不同听者拥有独立的播放位置、延迟线与混响状态，输出互不影响。
- 听者渲染失败（如参数非法）被捕获并隔离：该听者输出静音，失败计数 +1，其他听者不受影响。

---

## 5. REST API

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/mixer/sources` | 添加音源 |
| PUT | `/api/mixer/sources/{id}` | 更新音源参数 |
| DELETE | `/api/mixer/sources/{id}` | 移除音源 |
| POST | `/api/mixer/listeners` | 添加听者 |
| PUT | `/api/mixer/listeners/{id}` | 更新听者参数 |
| DELETE | `/api/mixer/listeners/{id}` | 移除听者 |
| POST | `/api/mixer/render/{listenerId}?blocks=N` | 渲染 N 个块，返回 WAV |
| POST | `/api/mixer/effects/doppler?enabled=bool` | 开关多普勒 |
| POST | `/api/mixer/effects/reverb?enabled=bool` | 开关混响 |
| GET | `/api/mixer/status` | 状态查询 |
| POST | `/api/mixer/reset` | 重置全部状态 |

### 5.1 音源请求体示例

```json
{
  "position": {"x": 0, "y": 0, "z": 10},
  "gain": 1.0,
  "refDistance": 1.0,
  "maxDistance": 100.0,
  "rolloff": 1.0,
  "reverbSend": 0.2,
  "waveform": "sine",
  "frequency": 440.0,
  "durationSeconds": 1.0,
  "loop": true
}
```

支持的波形：`sine`、`square`、`sawtooth`、`noise`（确定性 LCG 噪声）、`silence`。

### 5.2 听者请求体示例

```json
{
  "position": {"x": 0, "y": 0, "z": 0},
  "forward": {"x": 0, "y": 0, "z": 1},
  "up": {"x": 0, "y": 1, "z": 0},
  "gain": 1.0
}
```

---

## 6. 构建与测试

```bash
mvn compile        # 编译
mvn test           # 运行全部测试
mvn spring-boot:run # 启动服务
```

### 测试覆盖

| 测试类 | 覆盖点 |
|--------|--------|
| `SpatialAudioMixerTest` | 方位、距离衰减、移动平滑、听者独立、确定性 |
| `BlockContinuityTest` | 分块渲染 == 一次性渲染、块边界无爆音 |
| `EffectBypassTest` | 多普勒 / 混响开关旁路与基线一致、混响尾巴 |
| `ConcurrentUpdateTest` | 快照不可变、块内一致性、并发更新无异常 |
| `DegradationTest` | 强制级别确定、自动升级、输出有效 |
| `SourceRemovalTest` | 移除无残留 / 无串音、可听范围平滑过渡 |
| `ListenerIsolationTest` | 单听者故障隔离、未知听者处理 |
| `MixerControllerTest` | REST 全流程、效果开关、状态查询 |

---

## 7. 设计约束与已知限制

- 仰角被忽略（仅水平面 VBAP）。
- 混响为单声道输入、湿信号叠加到所有声道（非空间化）。
- 降级自动模式的级别选择依赖墙钟时间；固定级别下输出确定。
- 半采样率降级模式下混响与多普勒工作在 `sr/2`，音质下降（降级语义的预期行为）。
- 多普勒延迟线最大长度由 `maxDistance / speedOfSound * sampleRate` 决定；超出 `maxDistance` 的音源增益为 0，延迟线仍保留但不影响输出。
