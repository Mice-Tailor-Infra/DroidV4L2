# DroidV4L2: Universal Android Camera Source

**DroidV4L2** transforms your spare Android device into a high-performance, ultra-low-latency wireless webcam for Linux.

Unlike generic IP camera apps, DroidV4L2 is purpose-built for **professional low-latency usage**, supporting SRT (Secure Reliable Transport) for a rock-solid Linux bridge and RTSP for universal compatibility.

[中文说明](#中文说明)

---

## 🔥 Key Features

- **Multi-Protocol Power**:
  - **SRT (Caller)**: Optimized for Linux `v4l2loopback`. Minimal latency, high resilience.
  - **RTSP (Server)**: Acts as a standard IP Camera. Plug-and-play with VLC, OBS, and NVRs.
  - **MJPEG (HTTP)**: Universal fallback mode. Works in any browser without plugins.
  - **Broadcast Mode**: Stream to SRT and RTSP **simultaneously** from a single hardware encoder.
- **Auto-Discovery (mDNS)**: One-click connection. No more manual IP typing - the app automatically finds the Linux bridge.
- **Service Mode**: Supports true background/screen-off streaming. Save battery and prevent accidental touches.
- **Auto-Rotation**: Linux bridge automatically rotates landscape camera feeds to portrait, ensuring correct orientation for desktop apps.
- **"Caps Lockdown" Architecture**: Seamlessly switch between **H.264**, **H.265 (HEVC)**, and **MJPEG** at runtime without freezing the Linux virtual camera device.
- **Always-On SMPTE Bars**: Automatically displays professional color bars when the stream is disconnected.
- **Ultra-Low Latency**: Custom tuned `MediaCodec` parameters for <50ms glass-to-glass latency.
- **Hardware Accelerated**: Full utilization of Android hardware encoders.
- **Plug-and-Play Linux Bridge**: Auto-loads kernel modules (`v4l2loopback`) on startup.

## 🏗 Project Structure

- **`/android-app`**: CameraX + MediaCodec/MJPEG sender.
- **`/linux-app`**: Rust + GStreamer bridge to feed `/dev/videoX`.

## 🚀 Quick Start (Linux Bridge)

1. **Install Dependencies**:
   ```bash
   sudo modprobe v4l2loopback video_nr=10 card_label="DroidV4L2" exclusive_caps=1
   sudo apt install gstreamer1.0-plugins-good gstreamer1.0-plugins-bad gstreamer1.0-libav
   ```
2. **Run Bridge**:
   ```bash
   cd linux-app
   cargo run --release -- -4 5000 -5 5001 --device /dev/video10
   ```
   *(Optional) To enable MJPEG support:* `cargo run --release -- --mjpeg http://PHONE_IP:8080/`
3. **Open App**: Select **SRT**, **RTSP**, or **MJPEG**, enter your IP, and hit **Apply**.

---

<a name="中文说明"></a>

## 🔥 核心特性

- **多协议支持**:
  - **SRT (Caller)**: 专为 Linux `v4l2loopback` 设计，极低延迟，网络抗抖动强。
  - **RTSP (Server)**: 让手机变成标准 IP Camera，支持 VLC、OBS、群晖 NAS。
  - **MJPEG (HTTP)**: 通用兼容模式，无需插件即可在任何浏览器中播放。
  - **广播模式 (Broadcast)**: 支持一鱼两吃，同时推流到 SRT 和 RTSP。
- **自动发现 (Auto-Discovery)**: 内置 mDNS 客户端，一键自动搜索 Linux Bridge IP。
- **服务模式 (Service Mode)**: 支持真正的后台推流和息屏推流，省电防误触。
- **自动旋转 (Auto-Rotation)**: Linux 端自动将横屏采集的画面旋转为竖屏，完美适配桌面应用。
- **"Caps Lockdown" 架构**: 支持运行时无缝切换 H.264/H.265/MJPEG，Linux 虚拟摄像头永不掉线。
- **动态 SMPTE 彩条**: 断流时自动填充彩条，防止黑屏。
- **极低延迟**: 深度优化的参数，实现 <50ms 端到端延迟。
- **硬件加速**: 充分利用 Android 硬件编码器。
- **即插即用**: Linux 端自动加载 `v4l2loopback` 模块。

## 🏗 项目结构

- **`/android-app`**: 基于 CameraX 和 MediaCodec 的发送端，具备协议抽象层。
- **`/linux-app`**: 基于 Rust 和 GStreamer 的桥接端，负责将流写入 `/dev/videoX`。

---
*Maintained by cagedbird043. Built for performance.*