# Rust Bridge API (CLI)

Linux 端程序是一个标准的命令行工具 (CLI)。

## 命令行参数 (Arguments)

该程序基于 `clap` 库构建。

| 简写 | 全称 | 默认值 | 描述 |
| :--- | :--- | :--- | :--- |
| `-4` | `--port-srt` | `5000` | SRT 监听端口 |
| `-5` | `--port-rtsp`| `5001` | RTSP 服务端口 |
| `-d` | `--device` | `/dev/video0` | 输出的 V4L2 设备节点路径 |
| `-v` | `--verbose` | `false` | 开启详细 GStreamer 调试日志 |

## 使用示例

### 基础用法
```bash
./droid_v4l2_bridge --device /dev/video10
```

### 自定义端口
```bash
./droid_v4l2_bridge -4 9000 -d /dev/video20
```

### 调试模式
```bash
RUST_BACKTRACE=1 ./droid_v4l2_bridge -v
```
这将打印所有 GStreamer 状态变化和数据流事件。
