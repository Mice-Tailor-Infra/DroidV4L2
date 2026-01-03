# Ultra-Low Latency Tuning Guide

DroidV4L2's mission is to challenge the physical limits of wireless transmission. To achieve < 50ms Glass-to-Glass Latency, every link in the chain needs to be squeezed.

## 📶 Network Layer (The Physics)

**This is the biggest bottleneck.**

1.  **Use 5GHz WiFi**: Mandatory. Do not use 2.4GHz; interference is too high and latency unstable.
2.  **Exclusive Channel**: If possible, connect the phone to a dedicated router or hotspot.
3.  **USB Tethering (Wired Option)**: If you can tolerate a cable, USB Tethering offers lower and extremely stable latency (< 20ms) compared to WiFi.

## 🤖 Android Optimization

In App Settings:

-   **Resolution**: Choose `1280x720` over `1920x1080`. 720p encodes faster, uses less bandwidth, and looks almost as good.
-   **Codec**: Prefer **H.265 (HEVC)**. Half the bandwidth for the same quality.
-   **Bitrate**: Set to **2000-4000 Kbps**. Excessive bitrate fills network buffers and causes congestion.

## 🐧 Linux Optimization

### 1. Kernel Parameters
Ensure your V4L2 Loopback module is correctly configured.

```bash
# exclusive_caps=1 prevents Chrome/Zoom from fighting for device control
options v4l2loopback video_nr=10 card_label="DroidCam" exclusive_caps=1
```

### 2. Pipeline Parameters
Our Rust bridge uses aggressive defaults, but you can tweak `linux-app/src/pipeline.rs`:

-   `latency=20`: SRT receive buffer (ms). If jitter is high, increase to 50 or 100 for smoothness at the cost of latency.
-   `sync=false`: Disabling sync in `v4l2sink` is mandatory.

## 📊 How to Measure Latency?

The hardcore method:
1.  Open a millisecond stopwatch on your Linux screen.
2.  Point DroidV4L2 at this screen.
3.  Use another camera (or screenshot) to capture both the "Real Screen" and "OBS Preview Window".
4.  The difference between the two numbers is the true Glass-to-Glass latency.
