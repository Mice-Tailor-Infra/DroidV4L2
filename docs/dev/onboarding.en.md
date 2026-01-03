# Developer's Onboarding Guide

Welcome to the DroidV4L2 developer community. The project consists of an Android (Kotlin) sender and a Linux (Rust) receiver.

## 🛠 Environment Setup

### 1. Android (Sender)
- **IDE**: Android Studio Jellyfish+ or later.
- **SDK**: API Level 34 (Android 14) for compile, min SDK 24 (7.0).
- **Core Libs**: 
    - [TinyRtspKt](https://github.com/Mice-Tailor-Infra/TinyRtspKt): Ensure it's properly included.
    - CameraX: For video capturing.

### 2. Linux (Receiver)
- **Language**: Rust 1.70+.
- **System Packages**:
    ```bash
    # Ubuntu/Debian Example
    sudo apt install libgstreamer1.0-dev libgstreamer-plugins-base1.0-dev \
                     gstreamer1.0-plugins-good gstreamer1.0-plugins-bad \
                     gstreamer1.0-libav v4l2loopback-dkms
    ```

## 🚀 Development Workflow

1. **V4L2 Setup**: 
   ```bash
   sudo modprobe v4l2loopback video_nr=10 card_label="DroidCam" exclusive_caps=1
   ```
2. **Start Bridge**: 
   ```bash
   cd linux-app
   cargo run --release -- -4 5000 -5 5001 --device /dev/video10
   ```
3. **Android Connect**: Run the App, enter PC IP, select protocol, and click Apply.

## 🧪 Debugging Tips

- **Verify Stream**: Use `gst-launch-1.0 v4l2src device=/dev/video10 ! videoconvert ! autovideosink` to test the V4L2 device.
- **Latency Analysis**: Monitor Android logs for `KEY_LATENCY` effectiveness.
- **Coroutine Tracing**: Monitor RTSP signals within `TinyRtspKt` using Timber or Logcat.

---

> [!TIP]
> See [Architecture](../architecture/pipeline.md) for deeper implementation details.
