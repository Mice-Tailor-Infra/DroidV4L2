# DroidV4L2: High-Performance Android to Linux Virtual Camera

**DroidV4L2** turns your Android device into a low-latency, high-quality wireless webcam for Linux.

!!! tip "Latency Optimized"
    Optimized for <50ms glass-to-glass latency on 5GHz WiFi. Fixed with `KEY_LATENCY=0` (API 26+) and aggressive SRT tuning.

## 🌟 Key Features

*   **Ultra-Low Latency**: Sub-50ms streaming experience.
*   **Multi-Protocol Support**: 
    - **SRT (Caller)**: Pro-grade streaming with 30ms optimized buffering.
    - **RTSP (Server)**: Powered by our own **[TinyRtspKt](architecture/tinyrtsp.md)** with native HEVC support.
    - **MJPEG (Fallback)**: Maximum compatibility mode.
*   **Always-On Screensaver**: SMPTE color bars for stable V4L2 experience.
*   **Seamless Codec Switching**: Hot-swap H.264/H.265 without freezing.

## 🏗 Engineering Excellence

For deep dives into the project:
- [GStreamer Pipeline](architecture/pipeline.md): Analysis of the Linux video chain.
- [TinyRtspKt Deep Dive](architecture/tinyrtsp.md): Mysteries of our custom HEVC RTSP server.
- [Low Latency Guide](guide/low-latency.md): How we squeezed out every millisecond of performance.

## 🚀 Quick Start

1. **Linux**: Start the Bridge.
2. **Android**: Enter IP and click Apply.

> For more details, see [Developer's Onboarding](dev/onboarding.md).
