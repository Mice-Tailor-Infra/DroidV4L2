# DroidV4L2: Universal Android Camera Source

**DroidV4L2** transforms your spare Android device into a high-performance, ultra-low-latency wireless webcam for Linux.

Unlike generic IP camera apps, DroidV4L2 is purpose-built for **professional low-latency usage**, supporting SRT (Secure Reliable Transport) for a rock-solid Linux bridge and RTSP for universal compatibility.

[中文说明](#中文说明)

---

## 🔥 Key Features

- **Multi-Protocol Power**:
  - **SRT (Caller)**: Optimized for Linux `v4l2loopback`. Minimal latency, high resilience.
  - **RTSP (Server)**: Acts as a standard IP Camera. Plug-and-play with VLC, OBS, and NVRs.
  - **Broadcast Mode**: Stream to SRT and RTSP **simultaneously** from a single hardware encoder.
- **Auto-Discovery (mDNS)**: One-click connection. No more manual IP typing - the app automatically finds the Linux bridge.
- **Service Mode**: Supports true background/screen-off streaming. Save battery and prevent accidental touches without stopping the stream.
- **"Caps Lockdown" Architecture**: Seamlessly switch between **H.264** and **H.265 (HEVC)** at runtime without freezing the Linux virtual camera device.
- **Always-On SMPTE Bars**: Automatically displays professional color bars when the stream is disconnected. No more "Blank Screen" in OBS/Zoom.
- **Ultra-Low Latency**: Custom tuned `MediaCodec` parameters (1s GOP, Low-delay flags) for <50ms glass-to-glass latency.
- **Hardware Accelerated**: Full utilization of Android hardware encoders for 1080p 60FPS performance.
- **Plug-and-Play Linux Bridge**: Auto-loads kernel modules (`v4l2loopback`) on startup. No complex setup commands needed.

## 🏗 Project Structure

- **`/android-app`**: CameraX + MediaCodec sender with protocol abstraction.
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
3. **Open App**: Select **SRT** or **RTSP**, enter your IP, and hit **Apply**.

---

<a name="中文说明"></a>

## 🔥 核心特性

- **多协议支持**:
  - **SRT (Caller)**: 专为 Linux `v4l2loopback` 设计，极低延迟，网络抗抖动强。
  - **RTSP (Server)**: 让手机变成标准 IP Camera，支持 VLC、OBS、群晖 NAS、Home Assistant。
  - **广播模式 (Broadcast)**: 支持一路编码同时推流到 SRT 和 RTSP，一鱼两吃。
- **自动发现 (Auto-Discovery)**: 内置 mDNS 客户端，一键自动搜索局域网内的 Linux Bridge，告别手动输 IP。
- **服务模式 (Service Mode)**: 支持真正的后台推流和息屏推流，省电且防误触。
- **"Caps Lockdown" 架构**: 支持在运行时无缝切换 **H.264** 和 **H.265 (HEVC)** 编码，而不会导致 Linux 虚拟摄像头设备挂起。
- **动态 SMPTE 彩条**: 当流断开时自动填充专业彩条，避免视频会议或直播软件出现黑屏或报错。
- **极低延迟**: 深度优化的 `MediaCodec` 参数（1秒 GOP，低延迟标志位），实现 <50ms 的端到端延迟。
- **硬件加速**: 充分利用 Android 硬件编码器，轻松实现 1080p 60FPS。
- **即插即用**: Linux 端自动加载 `v4l2loopback` 内核模块，无需记忆复杂的 `modprobe` 命令。

## 🏗 项目结构

- **`/android-app`**: 基于 CameraX 和 MediaCodec 的发送端，具备协议抽象层。
- **`/linux-app`**: 基于 Rust 和 GStreamer 的桥接端，负责将流写入 `/dev/videoX`。

---
*Maintained by cagedbird043. Built for performance.*