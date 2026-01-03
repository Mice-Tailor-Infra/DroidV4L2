# DroidV4L2: 桌面级高性能 Android-Linux 虚拟摄像头

**DroidV4L2** 将您的 Android 设备转换为 Linux 下的低延迟、高质量无线摄像头。

!!! tip "极致延迟优化"
    本项目针对 5G WiFi 环境进行了深度优化，端到端延迟低于 50ms。通过 `KEY_LATENCY=0` (API 26+) 和 SRT 协议激进调优实现。

## 🌟 核心特性

*   **超低延迟**: 亚 50ms 的玻璃到玻璃延迟。
*   **多协议支持**: 
    - **SRT (Caller)**: 具备 30ms 极致缓冲区控制的专业级协议。
    - **RTSP (Server)**: 由自研 **[TinyRtspKt](architecture/tinyrtsp.md)** 驱动，支持 HEVC 转码。
    - **MJPEG (Fallback)**: 万能兼容模式。
*   **实时屏保**: 自动 SMPTE 彩条，防止 V4L2 消费端报错。
*   **无缝编码切换**: 运行时 H.264/H.265 无感切换。

## 🏗 技术深度

如果您想深入了解本项目，请参阅：
- [GStreamer 管道详解](architecture/pipeline.md): 剖析 Linux 端的视频处理链路。
- [TinyRtspKt 源码剖析](architecture/tinyrtsp.md): 探究自研 H.265 RTSP 服务器的奥秘。
- [极致调优指南](guide/low-latency.md): 记录我们如何压榨出每一毫秒的性能。

## 🚀 快速开始

1. **Linux 端**: 启动 Bridge 代理。
2. **Android 端**: 输入 IP，点击 Apply。

> 更多细节请参考 [开发者上手指南](dev/onboarding.md)。
