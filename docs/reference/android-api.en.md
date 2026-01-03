# Android API Reference

Core logic is encapsulated in `com.cagedbird.droidv4l2` package.

## StreamingService

Foreground service managing the streaming lifecycle.

### `Methods`

#### `startStreaming(config: StreamConfig)`
Starts the streaming session.
- **config**: Configuration object containing Target IP, Port, Resolution, Codec.
- **Throws**: `CameraAccessException` if camera cannot be opened.

#### `stopStreaming()`
Stops current stream, releasing CameraX and MediaCodec resources.

#### `isStreaming(): Boolean`
Returns current streaming state.

## VideoEncoder

Core class for hardware H.264/H.265 encoding.

### `Properties`

| Name | Type | Description |
| :--- | :--- | :--- |
| `bitRate` | `Int` | Target bitrate (bps) |
| `frameRate` | `Int` | Target fps (30 or 60) |
| `mimeType` | `String` | `video/avc` or `video/hevc` |

### `Methods`

#### `requestKeyFrame()`
Immediately requests an IDR Key Frame. Called when packet loss is detected or new client connects.
