# DroidV4L2: High-Performance Android to Linux Virtual Camera

**DroidV4L2** turns your Android device into a low-latency, high-quality wireless webcam for Linux. It supports multiple protocols including SRT for pro-grade Linux integration and RTSP for universal compatibility with players like VLC and OBS.

## 🌟 Key Features

*   **Ultra-Low Latency**: Optimized for <50ms glass-to-glass latency on 5GHz WiFi.
*   **Multi-Protocol Support**: 
    *   **SRT (Caller)**: Pro-grade, low-latency streaming for the Linux Bridge.
    *   **RTSP (Server)**: Universal mode powered by **[TinyRtspKt](https://github.com/Mice-Tailor-Infra/TinyRtspKt)**. Supports standard H.264 and H.265 (HEVC).
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
    - Zero-dependency implementation.
    - Native HEVC RFC 7798 fragmentation (Type 49).
    - UDP unicast with parameter set injection for instant recovery.
*   **CameraX + MediaCodec**: Hardware-accelerated capturing and encoding (H.264/H.265).
*   **Latency Tuning**: 1s GOP interval and low-latency priority flags.

### Linux Bridge (Receiver)
*   **Rust + GStreamer**: High-performance pipeline management.
*   **Dual-Port Listening**: Ports 5000 (H.264) and 5001 (H.265).
*   **Pipeline Strategy**:
    *   **Caps Lockdown**: Forces `appsrc` to a fixed format (I420 1080p).
    *   **Screensaver Mode**: Uses a secondary `videotestsrc` pipeline to feed SMPTE bars when idle.

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

## 🐞 Debugging Guide (AI + User Workflow)

To ensure efficiency, we follow a strict **"AI Builds, User Runs"** protocol.

### Roles
*   **🤖 AI Agent**:
    *   Compiles Android APK (`./gradlew assembleDebug`).
    *   Compiles Linux Rust Bridge (`cargo build --release`).
    *   Fixes code based on logs provided by the user.
*   **👤 User**:
    *   Installs the APK on the device (`adb install ...`).
    *   Runs the Linux Bridge.
    *   **Opens Two Terminals** to monitor the system live.

### Recommended Terminal Setup

#### Terminal 1: Linux Bridge (Receiver)
Runs the Rust application to receive the stream.
```bash
cd linux-app
# Use RUST_LOG=info or debug for more details
RUST_LOG=info cargo run --release -- -4 5000 -5 5001 --device /dev/video10
```

#### Terminal 2: Android Logs (Sender)
Monitors the Android device output via ADB.
```bash
adb logcat -c  # Clear old logs
# Filter for key tags: Main App, SRT, RTSP, Encoder, and System Errors
adb logcat -v color -s DroidV4L2 SrtSender TinyRtspKt VideoEncoder System.err
```

## 🛠 Development History
*   **Jan 2026**:
    *   **Major Architecture Shift**: Migrated RTSP Server to **[TinyRtspKt](https://github.com/Mice-Tailor-Infra/TinyRtspKt)**.
        - Solved H.265 "Illegal Temporal ID" issues.
        - Implemented critical VPS/SPS/PPS injection.
    *   Stabilized 5ms reconnection latency.
    *   Fixed resolution switching using server-side `videoscale`.
    *   Added H.265 support via dual-port architecture.
    *   **Major Breakthrough**: Implemented "Caps Lockdown" to enable seamless runtime codec switching.
    *   **UX Upgrade**: Implemented SMPTE color bars as an "Always-On" screensaver for idle states.
    *   Implemented intelligent Watchdog to prevent caps thrashing.

## 🤖 Agent Sync & Handover
> **Shared State for Multi-Agent Collaboration (Gemini <-> Antigravity)**

*   **Last Agent**: Gemini
*   **Timestamp**: Jan 3, 2026
*   **Current Status**: 
    *   ✅ **TinyRtspKt Migration**: Completed & Verified. The app now uses `com.github.Mice-Tailor-Infra:TinyRtspKt:v1.0.6`.
    *   ✅ **Infrastructure**: `gradlew` and build scripts are fully patched.
*   **Next Task (Handover to Antigravity)**:
    *   **Objective**: Implement **Phase 3: Broadcast Mode**.
    *   **Context**: We need a `PacketDuplicator` class (implementing `VideoSender`) to composite `SrtSender` and `RtspServerSender`.
    *   **Instruction**: Modify `MainActivity` to instantiate this duplicator when the user selects the new "Broadcast" option. **No changes needed to `VideoEncoder`**.

---
*Project maintained by cagedbird043.*
