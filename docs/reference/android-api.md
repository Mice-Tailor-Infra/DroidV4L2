# Android API 参考

DroidV4L2 的核心逻辑封装在 `com.cagedbird.droidv4l2` 包中。

## StreamingService

前台服务，负责维护整个推流生命周期。

### `Methods`

#### `startStreaming(config: StreamConfig)`
启动推流服务。
- **config**: 包含 IP 地址、端口、分辨率、编码类型的配置对象。
- **Throws**: `CameraAccessException` 如果无法占用摄像头。

#### `stopStreaming()`
停止当前推流，释放 CameraX 和 MediaCodec 资源。

#### `isStreaming(): Boolean`
当前是否处于推流状态。

## VideoEncoder

负责 H.264/H.265 硬编码的核心类。

### `Properties`

| 名称 | 类型 | 描述 |
| :--- | :--- | :--- |
| `bitRate` | `Int` | 目标比特率 (bps) |
| `frameRate` | `Int` | 目标帧率 (通常为 30 或 60) |
| `mimeType` | `String` | `video/avc` 或 `video/hevc` |

### `Methods`

#### `requestKeyFrame()`
立即请求生成一个 IDR 关键帧。通常在检测到丢包或客户端新连接时调用。
