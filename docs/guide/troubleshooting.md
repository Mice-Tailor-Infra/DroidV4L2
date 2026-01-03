# 故障排查 (Troubleshooting)

## ❌ 无法连接

**症状**: Android 端点击连接后一直显示 "Connecting..." 或立即断开。

**解决方案**:
1.  **防火墙检查**: Linux 端是否方行了 UDP 5000-5001 端口？
    ```bash
    sudo ufw allow 5000/udp
    sudo ufw allow 5001/udp
    ```
2.  **IP地址验证**: 确保手机和电脑在同一网段，且手机能够 Ping 通电脑 IP。
3.  **服务状态**: 确认 Linux Bridge 程序正在运行且显示 `Listening on :5000`。

## 📺 OBS/Zoom 无法识别摄像头

**症状**: `/dev/video10` 存在，但在 OBS 设备列表中找不到。

**解决方案**:
这通常是因为 `v4l2loopback` 加载参数未设置 `exclusive_caps=1`。
某些浏览器（如 Chrome）和应用会忽略未标记为“VideoCapture”的设备。

```bash
# 卸载并使用正确参数重新加载
sudo modprobe -r v4l2loopback
sudo modprobe v4l2loopback video_nr=10 card_label="DroidCam" exclusive_caps=1
```

## 🟩 绿屏/花屏

**症状**: 画面全是绿色或出现伪影。

**解决方案**:
1.  **关键帧丢失**: 尝试降低 Android 端的比特率（Bitrate），网络拥塞导致了丢包。
2.  **解码器错误**: GStreamer 管道可能未正确探测到流格式。重启 Android 端 App 以发送新的 SPS/PPS 头信息。
3.  **编解码不匹配**: 确保 Android 端选的编码 (H.264/H.265) 与 Linux 端预期一致（虽然现在我们支持自动切换，但在极端情况下可能需要重启 Bridge）。

## 🐢 延迟越来越高

**症状**: 刚开始延迟低，几分钟后延迟变大。

**解决方案**:
这是典型的“缓冲区堆积”。
-   检查 Linux 终端是否有 `WARNING: from element /GstPipeline:pipeline0/GstSrtSrc:srtsrc0: A lot of buffers are being dropped.`
-   这通常是网络带宽不足。请降低分辨率或比特率。
