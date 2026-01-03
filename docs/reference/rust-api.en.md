# Rust Bridge API (CLI)

The Linux application is a standard CLI tool.

## Command Line Arguments

Built with `clap`.

| Short | Long | Default | Description |
| :--- | :--- | :--- | :--- |
| `-4` | `--port-srt` | `5000` | SRT listening port |
| `-5` | `--port-rtsp`| `5001` | RTSP server port |
| `-d` | `--device` | `/dev/video0` | Target V4L2 device node path |
| `-v` | `--verbose` | `false` | Enable verbose GStreamer debug logs |

## Usage Examples

### Basic
```bash
./droid_v4l2_bridge --device /dev/video10
```

### Custom Ports
```bash
./droid_v4l2_bridge -4 9000 -d /dev/video20
```

### Debug Mode
```bash
RUST_BACKTRACE=1 ./droid_v4l2_bridge -v
```
Prints all GStreamer state changes and data flow events.
