# 🗺️ DroidV4L2 Project Roadmap

> **Vision**: Evolve from a specialized Linux bridge into the **"Universal Android Camera Source"** — a Swiss Army Knife capable of resurrecting old Android devices as high-performance, multi-protocol IP cameras.

## ✅ Completed Milestones (The "Epic" Foundation)
- [x] **H.265 (HEVC) Architecture**: High efficiency streaming via SRT.
- [x] **Caps Lockdown**: Seamless runtime codec switching without V4L2 freezes.
- [x] **Always-On Screensaver**: Signal loss fallback with SMPTE color bars.
- [x] **Latency Optimization**: <50ms glass-to-glass.

---

## 🚀 Future Horizons

### Phase 1: Architectural Abstraction (The "Interface" Update)
**Goal**: Decouple the UI from the specific network protocol.
- **Refactor**: Abstract `SrtSender` into a generic `VideoSender` interface.
  ```kotlin
  interface VideoSender {
      fun start()
      fun stop()
      fun sendData(data: ByteArray, type: Int)
      fun getDisplayInfo(): String // e.g., "srt://..." or "rtsp://..."
  }
  ```
- **UI Update**: Add a "Protocol Selector" (SRT Caller / RTSP Server / etc.).

### Phase 2: The "Universal Monitor" (RTSP Support)
**Goal**: Allow devices to function as standard IP Cameras for VLC, OBS, Synology NAS, and Home Assistant.
- **RTSP Server Mode**: 
  - Switch from "Push" (SRT Caller) to "Listen" (RTSP Server).
  - Use `pedroSG94/RootEncoder`'s RTSP server capabilities.
- **On-Screen Display**: Show the local RTSP URL (e.g., `rtsp://192.168.1.50:8554/live`) for easy pairing.
- **Use Case**: Turning drawer-bound Android phones into 4K security cameras.

### Phase 3: "Broadcast Mode" (Concurrent Output)
**Goal**: Stream to multiple destinations simultaneously using a single hardware encoder session.
- **Technical Challenge**: MediaCodec usually supports only one output surface.
- **Solution**: Packet replication at the application layer.
  - **Single Encoder** -> **Packet Duplicator** -> **SRT Sender (to Linux)** AND **RTSP Server (to Monitor)**.
- **Scenario**: Use the phone as a webcam for a Linux PC while simultaneously recording the feed via RTSP on a NAS.

### Phase 4: Ease of Use & Polish
- **Service Mode**: Run the server in a foreground Service to allow screen-off operation (battery saving).
- **Auto-Discovery**: mDNS/Bonjour support so VLC/OBS can "find" the Android camera automatically.

---
*Roadmap created on Jan 3, 2026, following the release of the "Caps Lockdown" update.*
