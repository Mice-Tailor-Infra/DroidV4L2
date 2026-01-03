# Troubleshooting

## ❌ Connection Failed

**Symptom**: Android stuck on "Connecting..." or disconnects immediately.

**Solution**:
1.  **Firewall**: Is UDP port 5000-5001 allowed on Linux?
    ```bash
    sudo ufw allow 5000/udp
    sudo ufw allow 5001/udp
    ```
2.  **IP Check**: Ensure Phone and PC are on the same subnet.
3.  **Service Status**: Verify Linux Bridge is running and `Listening on :5000`.

## 📺 OBS/Zoom Cannot Find Device

**Symptom**: `/dev/video10` exists, but fits not showing up in OBS device list.

**Solution**:
Missing `exclusive_caps=1` parameter for `v4l2loopback`. Chrome and some apps ignore devices not explicitly marked as video capture capability.

```bash
# Reload with correct params
sudo modprobe -r v4l2loopback
sudo modprobe v4l2loopback video_nr=10 card_label="DroidCam" exclusive_caps=1
```

## 🟩 Green Screen / Artifacts

**Symptom**: Image is green or corrupted blocks.

**Solution**:
1.  **Packet Loss**: Lower the Bitrate in Android app. Network congestion is dropping keyframes.
2.  **Decoder Error**: Restart Android App to resend SPS/PPS headers.
3.  **Codec Mismatch**: Ensure Android codec (H.264/H.265) matches Linux expectation (restart Bridge if hot-swap fails).

## 🐢 Latency Increasing Over Time

**Symptom**: Starts low, but delays build up after minutes.

**Solution**:
Typical "Buffer Bloat".
-   Check Linux logs for dropped buffer warnings.
-   Network cannot keep up with bitrate. Lower resolution or bitrate immediately.
