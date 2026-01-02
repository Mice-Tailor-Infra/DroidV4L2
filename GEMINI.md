# DroidV4L2: High-Performance Android to Linux Virtual Camera

**DroidV4L2** turns your Android device into a low-latency, high-quality wireless webcam for Linux. It uses the **SRT (Secure Reliable Transport)** protocol to stream H.264/H.265 video directly into a V4L2 loopback device, making it compatible with any Linux application (OBS, Zoom, Chrome, etc.).

## 🌟 Key Features

*   **Ultra-Low Latency**: Optimized for <50ms glass-to-glass latency on 5GHz WiFi.
*   **Always-On Screensaver**: Automatically displays professional SMPTE color bars when no client is connected, preventing V4L2 consumer errors.
*   **Seamless Codec Switching**: Runtime H.264/H.265 switching via "Caps Lockdown" (no V4L2 freezes).
*   **Dual Codec Support**:
    *   **H.264 (AVC)**: Maximum compatibility.
    *   **H.265 (HEVC)**: Half the bandwidth for the same quality (requires hardware support).
*   **Dynamic Resolution/FPS**: Switch between 480p/720p/1080p and 30/60 FPS on the fly.
*   **Robust Connection**: Auto-reconnects instantly; handles network jitter gracefully.
*   **Moonlight-Style UI**: Simple, effective settings panel on Android.

## 🏗 Architecture

### Android App (Sender)
*   **CameraX**: Captures raw frames (YUV).
*   **MediaCodec**: Hardware-accelerated encoding (H.264/H.265).
*   **SRT Protocol**: Uses `pedroSG94/RootEncoder` for reliable UDP transport.
*   **Logic**:
    *   **SrtSender**: Manages connection lifecycle. Extracts VPS/SPS/PPS NAL units for H.265 handshakes.
    *   **Latency Control**: Introduces a 200ms "tactical delay" on restart to sync with Linux receiver.

### Linux Bridge (Receiver)
*   **Rust + GStreamer**: High-performance pipeline management.
*   **Dual-Port Listening**:
    *   Port **5000**: Dedicated H.264 pipeline (`h264parse ! avdec_h264`).
    *   Port **5001**: Dedicated H.265 pipeline (`h265parse ! avdec_h265`).
*   **Pipeline Strategy**:
    *   **Caps Lockdown**: Forces `appsrc` to a fixed format (I420 1080p). This deceives downstream consumers (like OBS), preventing pipeline negotiation freezes during codec changes.
    *   **Screensaver Mode**: Uses a secondary `videotestsrc` pipeline to feed SMPTE bars when idle.
    *   **Persistent Sink**: Keeps `/dev/video10` open.
    *   **Dynamic Source**: Receives SRT stream, decodes, scales, and pushes to Sink.
    *   **Watchdog**: Monitors data flow. If idle for >500ms, switches to screensaver mode.

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

## 🛠 Development History
*   **Jan 2026**:
    *   Stabilized 5ms reconnection latency.
    *   Fixed resolution switching using server-side `videoscale`.
    *   Added H.265 support via dual-port architecture.
    *   **Major Breakthrough**: Implemented "Caps Lockdown" to enable seamless runtime codec switching.
    *   **UX Upgrade**: Implemented SMPTE color bars as an "Always-On" screensaver for idle states.
    *   Implemented intelligent Watchdog to prevent caps thrashing.

---
*Project maintained by cagedbird043.*
