# DroidV4L2: High-Performance Android to Linux Virtual Camera / 桌面级高性能 Android-Linux 虚拟摄像头

**DroidV4L2** turns your Android device into a low-latency, high-quality wireless webcam for Linux.
**DroidV4L2** 将您的 Android 设备转换为 Linux 下的低延迟、高质量无线摄像头。

## 🌏 Protocol / 协议
*   **Conversation**: 必须使用 **中文 (Chinese)** 与用户交流。
*   **Documentation**: 文档与提交记录需使用 **中英双语 (Bilingual: English & Chinese)**。

## 🌟 Key Features / 核心特性

*   **Ultra-Low Latency / 超低延迟**: Optimized for <50ms glass-to-glass latency on 5GHz WiFi. Fixed with `KEY_LATENCY=0` (API 26+) and aggressive SRT tuning.
    *   针对 5GHz WiFi 进行优化，端到端延迟 <50ms。通过 `KEY_LATENCY=0` (API 26+) 和激进的 SRT 调优实现。
*   **Multi-Protocol Support / 多协议支持**: 
    *   **SRT (Caller)**: Pro-grade, low-latency streaming for the Linux Bridge. Now with 30ms optimized buffering.
        *   专业级、低延迟流媒体，专为 Linux Bridge 优化。目前已配备 30ms 优化缓冲区。
    *   **RTSP (Server)**: Universal mode powered by **[TinyRtspKt](https://github.com/Mice-Tailor-Infra/TinyRtspKt)**. Supports standard H.264 and H.265 (HEVC).
        *   通用模式，由 **TinyRtspKt** 驱动。支持标准 H.264 和 H.265 (HEVC)。
    *   **MJPEG (Fallback)**: Robust HTTP stream for maximum compatibility.
        *   鲁棒的 HTTP 流，确保最大兼容性。
*   **Always-On Screensaver / 始终在线的屏保**: Automatically displays professional SMPTE color bars when no client is connected, preventing V4L2 consumer errors.
    *   当无客户端连接时自动显示专业 SMPTE 彩条，防止 V4L2 消费端报错。
*   **Seamless Codec Switching / 无缝编码切换**: Runtime H.264/H.265 switching via "Caps Lockdown" (no V4L2 freezes).
    *   通过 "Caps Lockdown" 实现运行时 H.264/H.265 无缝切换，解决 V4L2 冻结问题。
*   **Dual Codec Support / 双编码支持**:
    *   **H.264 (AVC)**: Maximum compatibility. / 最大兼容性。
    *   **H.265 (HEVC)**: Half the bandwidth for the same quality. / 同等画质下带宽减半。
*   **Dynamic Resolution/FPS / 动态分辨率与帧率**: Switch between 480p/720p/1080p and 30/60 FPS on the fly.
    *   支持在线切换 480p/720p/1080p 及 30/60 帧。

## 🏗 Architecture / 架构设计

### Android App (Sender) / Android 端 (发送者)
*   **VideoSender Interface**: Decoupled network layer allowing easy protocol switching.
    *   解耦的网络层，支持轻松切换协议。
*   **SRT Mode**: Uses `SrtClient` for pushed streams. / 使用 `SrtClient` 进行推流。
*   **CameraX + MediaCodec**: Hardware-accelerated capturing and encoding.
    *   硬件加速的视频采集与编码。
*   **Latency Tuning / 延迟调优**: 
    - Forced `KEY_LATENCY=0` for immediate frame delivery. / 强制开启 `KEY_LATENCY=0` 以实现即时出帧。
    - Optimized 1s GOP and high-priority encoding threads. / 优化的 1s GOP 及高优先级编码线程。
*   **Automated Stability / 自动化稳定性**: Full JUnit 5 + MockK unit test coverage for core logic.
    *   核心逻辑（如 `PacketDuplicator`, `ImageUtils` 等）已实现 JUnit 5 + MockK 单元测试全覆盖。

### Linux Bridge (Receiver) / Linux 端 (接收者)
*   **Rust + GStreamer**: High-performance pipeline management. / 高性能 GStreamer 管道管理。
*   **Modular Design / 模块化设计**:
    - `config`: Robust CLI argument parsing. / 强壮的命令行参数解析。
    - `state`: Thread-safe bridge state management and frame pushing. / 线程安全的 Bridge 状态管理与样本推送。
    - `pipeline`: Dynamic GStreamer string generation with `videoflip` rotation support. / 动态管道生成，支持 `videoflip` 旋转。
    - `utils`: System-level tools (mDNS, `v4l2loopback` auto-loading, etc). / 系统级工具（mDNS, v4l2loopback 自动加载等）。
*   **Pipeline Strategy / 管道策略**:
    *   **Caps Lockdown**: Forces `appsrc` to a fixed format (I420 1080p). / 强制 `appsrc` 固定格式。
    *   **Low Latency Decoder**: FFmpeg decoders tuned with balanced buffering (30ms). / 调优后的 FFmpeg 解码器。
*   **Stability / 稳定性**: Unit testing for config parsing and pipeline generation.
    *   为配置解析和管道生成实现了单元测试。

## 🚀 Getting Started / 快速开始

### Prerequisites / 前置条件
1.  **Linux**: `v4l2loopback` kernel module installed.
    ```bash
    sudo modprobe v4l2loopback video_nr=10 card_label="DroidCam" exclusive_caps=1
    sudo apt install gstreamer1.0-plugins-good gstreamer1.0-plugins-bad gstreamer1.0-libav
    ```
2.  **Android**: Device with Android 7.0+.

### Running / 运行

1.  **Start Linux Bridge / 启动 Linux Bridge**:
    ```bash
    cd linux-app
    cargo run --release -- -4 5000 -5 5001 --device /dev/video10
    ```

2.  **Start Android App / 启动 Android 端**:
    *   Enter Linux IP. / 输入 Linux IP 地址。
    *   Click **Apply Settings**. / 点击应用设置。

## 🛠 Development History / 开发历史
*   **Jan 2026**:
    *   **Phase 22: Stability & Refactor / 稳定性与重构**:
        - **Android**: JUnit 5/MockK, `ImageUtils` Extraction, UI state fixes. / 测试实施、逻辑提取与 UI 修复。
        - **Linux**: Modularized architecture (`config`, `state`, `pipeline`, `utils`). / 模块化重构。
    *   **Phase 23: SRT Performance Tuning / SRT 性能优化**:
        - **Latency**: `KEY_LATENCY=0` flags, optimized SRT buffering (30ms). / 零延迟标志与 30ms 缓冲优化。
        - **Orientation / 方向**: Fixed rotation issues in the Linux Bridge. / 修复 Linux 端画面旋转。

---
*Project maintained by cagedbird043.*
