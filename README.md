# spatial-audio-mixer-java

服务端空间音频混音服务（Spring Boot 3 / Java 17）。支持多音源、多听者，体现
方位 / 距离 / 运动（多普勒）听感与空间混响；按任意采样块渲染且结果与分块方式
无关；支持并发参数更新、运行期增删音源、确定性过载降级。

- 设计、参数含义、确定性与降级语义：[`docs/SPATIAL_AUDIO.md`](docs/SPATIAL_AUDIO.md)
- 核心引擎：`src/main/java/com/github/huchangjun118/spatialaudiomixerjava/engine/SpatialMixer.java`
- 测试：`src/test/java/com/github/huchangjun118/spatialaudiomixerjava/tests/`

## 快速开始

```bash
mvn spring-boot:run
# 新增听者 / 音源
curl -XPOST localhost:8080/api/mixer/listeners -H 'Content-Type: application/json' \
  -d '{"id":"L1","position":{"x":0,"y":0,"z":0}}'
curl -XPOST localhost:8080/api/mixer/sources -H 'Content-Type: application/json' \
  -d '{"id":"S1","position":{"x":3,"y":0,"z":8},"gain":0.8}'
# 推流（samples 为 -1..1 的单声道浮点数组）并渲染
curl -XPOST localhost:8080/api/mixer/input   -H 'Content-Type: application/json' \
  -d '{"sourceId":"S1","samples":[0,0.1,...]}'
curl -XPOST 'localhost:8080/api/mixer/render?samples=512'
```

## 构建与测试

```bash
mvn test      # 20 个测试：引擎一致性 / 并发 / 降级 / 开关，以及 HTTP 集成
```
