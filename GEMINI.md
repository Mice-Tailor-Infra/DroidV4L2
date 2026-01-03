# DroidV4L2: 桌面级高性能 Android-Linux 虚拟摄像头

**DroidV4L2** 将您的 Android 设备转换为 Linux 下的低延迟、高质量无线摄像头。

## 🌟 核心特性

*   **超低延迟**: 针对 5G WiFi 环境深度优化，端到端延迟低于 50ms。通过 `KEY_LATENCY=0` (API 26+) 和 SRT 协议激进调优实现。
*   **多协议支持**: 
    *   **SRT (Caller)**: 专业级、超低延迟流媒体协议，专为 Linux Bridge 调优，具备 30ms 的极致缓冲区控制。
    *   **RTSP (Server)**: 通用播放模式，由 **[TinyRtspKt](https://github.com/Mice-Tailor-Infra/TinyRtspKt)** 驱动，支持标准的 H.264 和 H.265 (HEVC) 编码。
    *   **MJPEG (Fallback)**: 备用 HTTP 视频流，确保在任何环境下都能具备基本的兼容性。
*   **实时屏保**: 当无客户端连接时，自动显示专业 SMPTE 彩条，防止 V4L2 消费端（如 OBS 或 Zoom）出现黑屏或报错。
*   **无缝编码切换**: 利用 "Caps Lockdown" 技术，支持在运行时无缝切换 H.264 和 H.265 编码，无需重新加载虚拟摄像头设备。
*   **双编码支持**:
    *   **H.264 (AVC)**: 适配性最强，确保在各种 Linux 系统下都能正常运行。
    *   **H.265 (HEVC)**: 在同等画质下仅需一半带宽，非常适合高分辨率传输。
*   **动态参数调节**: 支持在运行时动态修改分辨率（480p/720p/1080p）和帧率（30/60 FPS）。
*   **Moonlight 风格 UI**: 简洁高效的 Android 设置面板，类似 Moonlight 客户端的极简交互。

## 🏗 技术架构

### Android App (发送端)
*   **VideoSender 接口**: 解耦的网络传输层，支持轻松扩展不同流媒体协议。
*   **SRT 模式**: 使用 `SrtClient` 进行高性能推流。
*   **CameraX + MediaCodec**: 纯硬件加速的视频采集与编码链路。
*   **延迟调优**: 
    - 强制 `KEY_LATENCY=0` 实现极速出帧。
    - 优化的 1s GOP (关键帧间隔) 和高优先级编码线程。
*   **稳定性保障**: 核心逻辑（如 `PacketDuplicator`, `ImageUtils`）已实现完整的 JUnit 5 + MockK 单元测试覆盖。

### Linux Bridge (接收端)
*   **Rust + GStreamer**: 采用高性能的 Rust 语言结合 GStreamer 框架进行管道管理。
*   **模块化重构**:
    - `config`: 健壮的命令行参数解析逻辑。
    - `state`: 线程安全的 Bridge 状态管理，负责接收样本并推送至设备。
    - `pipeline`: 动态管道生成器，支持 `videoflip` 旋转修正。
    - `utils`: 系统级工具，包括 mDNS 发布、`v4l2loopback` 自动加载等。
*   **管道策略**:
    - **Caps Lockdown**: 强制 `appsrc` 输出固定格式（I420 1080p），确保 V4L2 设备稳定。
    - **低延迟解码**: 针对 FFmpeg 解码器进行了深度调优，配合 30ms 平衡缓冲区实现高速响应。
*   **自动化测试**: 涵盖了配置解析和管道生成的单元测试。

## 🚀 快速开始

### 系统准备
1.  **Linux**: 确保已安装 `v4l2loopback` 内核模块。
    ```bash
    sudo modprobe v4l2loopback video_nr=10 card_label="DroidCam" exclusive_caps=1
    sudo apt install gstreamer1.0-plugins-good gstreamer1.0-plugins-bad gstreamer1.0-libav
    ```
2.  **Android**: 需要 Android 7.0+ 系统（HEVC 编码需较新硬件支持）。

### 如何运行

1.  **启动 Linux Bridge**:
    ```bash
    cd linux-app
    cargo run --release -- -4 5000 -5 5001 --device /dev/video10
    ```

2.  **启动 Android App**:
    - 输入 Linux 端的 IP 地址。
    - 选择所需的分辨率、帧率及编码。
    - 点击 **Apply Settings** 即可开始同步。

---
---
---

# DroidV4L2: High-Performance Android to Linux Virtual Camera

**DroidV4L2** turns your Android device into a low-latency, high-quality wireless webcam for Linux.

## 🌟 Key Features

*   **Ultra-Low Latency**: Optimized for <50ms glass-to-glass latency on 5GHz WiFi. Fixed with `KEY_LATENCY=0` (API 26+) and aggressive SRT tuning.
*   **Multi-Protocol Support**: 
    *   **SRT (Caller)**: Pro-grade, low-latency streaming for the Linux Bridge. Now with 30ms optimized buffering.
    *   **RTSP (Server)**: Universal mode powered by **[TinyRtspKt](https://github.com/Mice-Tailor-Infra/TinyRtspKt)**. Supports standard H.264 and H.265 (HEVC).
    *   **MJPEG (Fallback)**: Robust HTTP stream for maximum compatibility.
*   **Always-On Screensaver**: Automatically displays professional SMPTE color bars when no client is connected, preventing V4L2 consumer errors.
*   **Seamless Codec Switching**: Runtime H.264/H.265 switching via "Caps Lockdown" (no V4L2 freezes).
*   **Dual Codec Support**:
    *   **H.264 (AVC)**: Maximum compatibility.
    *   **H.265 (HEVC)**: Half the bandwidth for the same quality.
*   **Dynamic Resolution/FPS**: Switch between 480p/720p/1080p and 30/60 FPS on the fly.
*   **Moonlight-Style UI**: Simple, effective settings panel on Android.

## 🏗 Architecture

### Android App (Sender)
*   **VideoSender Interface**: Decoupled network layer allowing easy protocol switching.
*   **SRT Mode**: Uses `SrtClient` for pushed streams.
*   **RTSP Mode**: Uses custom `TinyRtspKt` library.
*   **CameraX + MediaCodec**: Hardware-accelerated capturing and encoding.
*   **Latency Tuning**: 
    - Forced `KEY_LATENCY=0` for immediate frame delivery.
    - Optimized 1s GOP and high-priority encoding threads.
*   **Automated Stability**: Full JUnit 5 + MockK unit test coverage for core logic (`PacketDuplicator`, `ImageUtils`, etc.).

### Linux Bridge (Receiver)
*   **Rust + GStreamer**: High-performance pipeline management.
*   **Modular Design**:
    - `config`: Robust CLI argument parsing.
    - `state`: Thread-safe bridge state management and frame pushing.
    - `pipeline`: Dynamic GStreamer string generation with `videoflip` rotation support.
    - `utils`: System-level tools (mDNS, `v4l2loopback` auto-loading, plugin checks).
*   **Pipeline Strategy**:
    *   **Caps Lockdown**: Forces `appsrc` to a fixed format (I420 1080p).
    *   **Low Latency Decoder**: FFmpeg decoders tuned with balanced buffering (30ms) for high response speed.
*   **Stability**: Unit testing for config parsing and pipeline generation.

## 🚀 Getting Started

### Prerequisites
1.  **Linux**: `v4l2loopback` kernel module installed.
    ```bash
    sudo modprobe v4l2loopback video_nr=10 card_label="DroidCam" exclusive_caps=1
    sudo apt install gstreamer1.0-plugins-good gstreamer1.0-plugins-bad gstreamer1.0-libav
    ```
2.  **Android**: Device with Android 7.0+ (HEVC requires newer hardware).

### Running

1.  **Start Linux Bridge**:
    ```bash
    cd linux-app
    cargo run --release -- -4 5000 -5 5001 --device /dev/video10
    ```

2.  **Start Android App**:
    *   Enter Linux IP.
    *   Select Resolution/FPS/Codec.
    *   Click **Apply Settings**.

---
*Project maintained by cagedbird043.*
