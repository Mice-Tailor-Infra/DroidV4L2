# TinyRtspKt Deep Dive (Hearts of DroidV4L2)

**TinyRtspKt** is a lightweight, zero-dependency RTSP server engine custom-built for Android, serving as the core of DroidV4L2's high-performance video streaming.

## 🎯 Why TinyRtspKt?

During early research for DroidV4L2, we evaluated several open-source RTSP libraries but found they were either:
1. **Heavy Dependencies**: Required massive FFmpeg or C++ NDK toolchains.
2. **Poor HEVC Support**: Often limited to H.264, lacking complete RFC 7798 implementations for H.265.
3. **Legacy Memory Management**: Not optimized for Android's `ByteBuffer` reuse.

To solve these, we built TinyRtspKt.

## 🛠 Core Technical Features

### 1. Industrial RFC 7798 (HEVC) Implementation
The `HevcPacketizer` in source code (see `com.micetailor.tinyrtsp.rtp.packetizer` in the repo) handles complex NAL unit fragmentation. We manually implemented **Fragmentation Units (FU)** (Type 49) to manage frames larger than MTU.

### 2. Zero-Dependency Design
The library relies solely on **Kotlin Coroutines** for concurrency. It has a tiny binary footprint (< 200KB) and starts almost instantly.

### 3. Coroutine-Based Asynchronous Signaling
RTSP handshakes (DESCRIBE, SETUP, PLAY) are handled non-blockingly via coroutines, allowing the server to scale efficiently.

## 🔬 Key Source Components

- `RtspServer.kt`: The main entry point for listening and dispatching signals.
- `RtpPacketizer.kt`: Base class for encapsulating encoded data into RTP packets.
- `HevcPacketizer.kt`: The core implementation of H.265 fragmentation logic.

## 🚀 Contribution

Maintained independently at [Mice-Tailor-Infra/TinyRtspKt](https://github.com/Mice-Tailor-Infra/TinyRtspKt). PRs for performance optimizations are highly prioritized.
