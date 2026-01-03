# DroidV4L2: High-Performance Android-Linux Virtual Camera

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![Documentation](https://img.shields.io/badge/docs-miceworld.top-green.svg)](https://miceworld.top/DroidV4L2/)

**DroidV4L2** is a professional tool that turns your Android device into a low-latency, high-quality wireless webcam for Linux.

---

## 🌟 Core Features

- **Ultra-Low Latency**: End-to-end latency < 50ms via aggressive SRT & `KEY_LATENCY=0` tuning.
- **TinyRtspKt Engine**: Powered by our custom, zero-dependency HEVC RTSP server.
- **Modular Bridge**: High-performance Rust backend using GStreamer.
- **Seamless Switching**: Runtime codec hot-swapping (H.264/H.265) without freezes.

## 📖 Documentation

For in-depth guides, architecture analysis, and API references, please visit our official documentation portal:

👉 **[https://miceworld.top/DroidV4L2/](https://miceworld.top/DroidV4L2/)**

## 🚀 Quick Start

```bash
# Start Linux Bridge
cd linux-app
cargo run --release -- -4 5000 -5 5001 --device /dev/video10

# Then Open Android App and Connect
```

---

## 🏗 Project Ecosystem

- **[TinyRtspKt](https://github.com/Mice-Tailor-Infra/TinyRtspKt)**: Our custom RTSP implementation for Android.
- **[DroidV4L2-Bridge](https://github.com/Mice-Tailor-Infra/DroidV4L2)**: The repository you're currently in.

---

> [!NOTE]
> This project is maintained by **Mice-Tailor-Infra**. Licensed under **GPLv3**.
