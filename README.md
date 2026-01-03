# DroidV4L2: High-Performance Android to Linux Virtual Camera

**DroidV4L2** turns your Android device into a low-latency, high-quality wireless webcam for Linux.

## 🌏 Protocol / 协议
*   **Conversation**: 必须使用 **中文 (Chinese)** 与用户交流。
*   **Documentation**: 文档与提交记录需使用 **中英双语 (Bilingual: English & Chinese)**。

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

## 🐞 Debugging Guide (AI-Driven Workflow)

To maximize efficiency, the AI Agent now handles the entire build and deployment pipeline.

### Roles
*   **🤖 AI Agent**:
    *   **Builds**: Compiles APKs (`./gradlew assembleDebug`) and Rust binaries.
    *   **Deploys**: Installs APKs via ADB (`adb install -r ...`).
    *   **Runs**: Starts the app (`adb shell am start ...`) and the Linux bridge.
    *   **Monitors**: Reads logs directly via `adb logcat` or cargo output.
*   **👤 User**:
    *   **Visual Check**: Verifies if the video is visible on the phone or browser.
    *   **Physical Intervention**: Restarts the device if ADB freezes.

## 🛠 Development History
*   **Jan 2026**:
    *   **Phase 22: Stability & Refactor (稳定性与重构)**:
        - **Android**: Integrated JUnit 5/MockK, extracted `ImageUtils`, fixed UI state sync bugs.
        - **Linux**: Modularized `main.rs` into sub-modules (`config`, `state`, `pipeline`, `utils`).
    *   **Phase 23: SRT Performance Tuning (SRT 性能优化)**:
        - **Latency**: Implemented `KEY_LATENCY` flags and optimized SRT buffering (30ms).
        - **Orientation**: Fixed rotation issues in the Linux Bridge pipeline.

*   **Agent Sync & Handover**
> **Shared State for Multi-Agent Collaboration (Gemini <-> Antigravity)**

*   **Last Agent**: Antigravity
*   **Timestamp**: Jan 4, 2026 (Phase 23 Completed)
*   **Current Status**: 
    *   ✅ **Phase 22**: Unit Testing & Modularization Completed.
    *   ✅ **Phase 23**: Low-Latency SRT Tuning & Rotation Fix Completed.
*   **Next Task**:
    *   **Objective**: Maintenance or new features (e.g., Audio support or WebRTC re-visit).
    *   **Instruction**: System is stable with solid test coverage and modular architecture. 

---
*Project maintained by cagedbird043.*
