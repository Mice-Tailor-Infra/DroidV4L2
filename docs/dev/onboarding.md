# 开发者快速上手指南

欢迎来到 DroidV4L2 开发者阵营。本项目由 Android (Kotlin) 发送端和 Linux (Rust) 接收端组成。

## 🛠 环境搭建

### 1. Android 端 (sender)
- **工具**: Android Studio Jellyfish+ 或更高版本。
- **SDK**: API Level 34 (Android 14) 编译器，最低支持 API 24 (7.0)。
- **核心库**: 
    - [TinyRtspKt](https://github.com/Mice-Tailor-Infra/TinyRtspKt): 请确保以子目录或 Maven 形式正确引入。
    - CameraX: 负责视频流采集。

### 2. Linux 端 (receiver)
- **语言**: Rust 1.70+。
- **系统包**:
    ```bash
    # Ubuntu/Debian 示例
    sudo apt install libgstreamer1.0-dev libgstreamer-plugins-base1.0-dev \
                     gstreamer1.0-plugins-good gstreamer1.0-plugins-bad \
                     gstreamer1.0-libav v4l2loopback-dkms
    ```

## 🚀 联调试流程

1. **虚拟摄像头就绪**: 
   ```bash
   sudo modprobe v4l2loopback video_nr=10 card_label="DroidCam" exclusive_caps=1
   ```
2. **启动 Bridge**: 
   ```bash
   cd linux-app
   cargo run --release -- -4 5000 -5 5001 --device /dev/video10
   ```
3. **Android 连接**: 运行 App，输入 PC 的 IP 地址（确保在同一局域网），选择协议并点击 Apply。

## 🧪 调试技巧

- **检查数据流**: 使用 `gst-launch-1.0 v4l2src device=/dev/video10 ! videoconvert ! autovideosink` 测试 V4L2 设备是否有画面。
- **延迟分析**: 开启 Android 端的编码日志，关注 `KEY_LATENCY` 标志是否生效。
- **协程追踪**: 在 `TinyRtspKt` 源码中使用 Timber 或 Logcat 监控 RTSP 信令。

---

> [!TIP]
> 更多架构细节请查阅 [架构解析](../architecture/pipeline.md)。
