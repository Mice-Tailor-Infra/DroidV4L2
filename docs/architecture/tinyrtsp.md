# TinyRtspKt 深度剖析 (Hearts of DroidV4L2)

**TinyRtspKt** 是专门为 Android 平台打造的轻量级、零依赖 RTSP 服务器引擎，是 DroidV4L2 实现高性能视频串流的核心。

## 🎯 为什么需要 TinyRtspKt？

在 DroidV4L2 早期设计中，我们调研了许多开源 RTSP 库，但发现它们要么：
1. **依赖沉重**: 需要引入庞大的 FFmpeg 或 C++ NDK 编译链。
2. **HEVC 支持匮乏**: 许多库仅支持 H.264，对 H.265 (HEVC) 的 RFC 7798 规范实现不完整。
3. **内存管理落后**: 未针对 Android 的 `ByteBuffer` 复用进行优化。

为了解决这些痛点，我们自研了 TinyRtspKt。

## 🛠 核心技术特性

### 1. RFC 7798 (HEVC) 工业级实现
源码中的 `HevcPacketizer`（见源仓库 `com.micetailor.tinyrtsp.rtp.packetizer`）实现了完整的 NAL 单元拆包与组包逻辑。特别是在处理超过 MTU (1500 bytes) 的超大帧时，我们手动实现了 **Fragmentation Unit (FU)** 逻辑（Type 49）。

### 2. 零依赖设计
整个库仅依赖于 **Kotlin Coroutines** 实现并发，不依赖任何第三方音视频框架。这意味着它的二进制体积极小（< 200KB），且具备极高的启动速度。

### 3. 基于协程的异步信令处理
通过协程非阻塞地处理 DESCRIBE, SETUP, PLAY 等 RTSP 握手流程，确保服务器可以轻快地响应多个客户端的并发请求。

## 🔬 源码预览 (关键类)

- `RtspServer.kt`: 监听端口并分发信令的核心入口。
- `RtpPacketizer.kt`: 负责将原始编码数据封装进 RTP 包的基类。
- `HevcPacketizer.kt`: 核心中的核心，H.265 分片逻辑的所在地。

## 🚀 贡献与维护

该项目目前独立维护在 [Mice-Tailor-Infra/TinyRtspKt](https://github.com/Mice-Tailor-Infra/TinyRtspKt)。由于它与 DroidV4L2 的性能高度绑定，任何关于性能提升的 PR 我们都会优先审核。
