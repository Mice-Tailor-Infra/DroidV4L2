# DroidV4L2

将 Android 设备作为局域网内的 Linux V4L2 设备的解决方案。

## 项目架构

- **android-app**: 
    - 技术栈: Kotlin + CameraX + MediaCodec (H.264/H.265)。
    - 功能: 采集摄像头画面并以极低延迟推流。
- **linux-app**:
    - 技术栈: Rust + GStreamer + v4l2loopback。
    - 功能: 接收 Android 推流，解码并写入 `/dev/videoX` 虚拟设备。

## 当前进度

1. [x] 项目骨架初始化 (Android & Rust)
2. [ ] Android 端：CameraX 采集实现
3. [ ] Android 端：MediaCodec 编码与推流实现
4. [ ] Linux 端：GStreamer 接收流水线实现
5. [ ] Linux 端：v4l2loopback 写入实现
