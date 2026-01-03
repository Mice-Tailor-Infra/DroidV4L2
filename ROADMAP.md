# 🗺️ DroidV4L2 Project Roadmap

> **Vision**: Evolve from a specialized Linux bridge into the **"Universal Android Camera Source"** — a Swiss Army Knife capable of resurrecting old Android devices as high-performance, multi-protocol IP cameras.

## ✅ Completed Milestones

### The "Epic" Foundation (Core Stability)
- [x] **H.265 (HEVC) Architecture**: High efficiency streaming via SRT.
- [x] **Caps Lockdown**: Seamless runtime codec switching without V4L2 freezes.
- [x] **Always-On Screensaver**: Signal loss fallback with SMPTE color bars.
- [x] **Latency Optimization**: <50ms glass-to-glass (1s GOP, Low-Delay flags).

### Multi-Protocol Expansion (The "Swiss Army Knife" Update)
- [x] **Architectural Abstraction**: Introduced `VideoSender` interface to decouple UI from network logic.
- [x] **RTSP Server Support (HEVC Ready)**:
    - Replaced generic library with custom-built **[TinyRtspKt](https://github.com/Mice-Tailor-Infra/TinyRtspKt)** engine.
    - **Native HEVC (H.265)** support via RFC 7798 implementation.
    - Zero-latency UDP streaming with instant keyframe recovery.
    - **Open Sourced**: Published as a standalone library `com.github.Mice-Tailor-Infra:TinyRtspKt`.

---

## 🚀 Future Horizons

### Phase 3: "Broadcast Mode" (Concurrent Output)
**Goal**: Stream to multiple destinations simultaneously using a single hardware encoder session.
- **Technical Challenge**: MediaCodec output buffer reuse logic.
- **Solution**: Implement a `PacketDuplicator` class implementing `VideoSender`.
  - **Single Encoder** -> **Packet Duplicator** -> **[SRT Sender + RTSP Server]**.
- **Scenario**: Use the phone as a webcam for a Linux PC while simultaneously recording the feed via RTSP on a NAS.
- [x] **Implemented**: Concurrent SRT + RTSP streaming via `PacketDuplicator` (Jan 2026).

### Phase 4: Ease of Use & Polish (Verified v1.0.0)
- [x] **Auto-Modprobe**: Integrate `v4l2loopback` loading into the Linux app to eliminate manual `sudo modprobe` commands.
- [x] **Service Mode**: Run the server in a foreground Service to allow screen-off operation (battery saving) and background streaming.
- [x] **Auto-Discovery**: mDNS/Bonjour support so VLC/OBS can "find" the Android camera automatically.
- [x] **Stability V3**: Implement "Bind-Both-Always" strategy for robust camera lifecycle management.

### Phase 5: "The Ultimate Edge" (WebRTC)
**Goal**: Zero-install viewing in any web browser.
- **Implementation**: Embed a lightweight WebRTC signaling server.
- **Use Case**: Share a link `http://192.168.1.x:8080` and view the camera instantly in Chrome/Safari without VLC.

---
*Roadmap updated on Jan 3, 2026, after the successful merge of `feat/multi-protocol`.*