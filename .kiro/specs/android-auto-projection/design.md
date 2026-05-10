# Design: Android Auto Phone-Side Projection

## Architecture Overview

```
┌─────────────────────────────────────────────────────────┐
│                    Android Phone                          │
│                                                          │
│  ┌──────────────────────────────────────────────────┐   │
│  │              ProjectionService                     │   │
│  │         (Foreground Service)                       │   │
│  │                                                    │   │
│  │  ┌────────────┐  ┌────────────┐  ┌────────────┐  │   │
│  │  │ Transport  │  │  Protocol  │  │  Channel   │  │   │
│  │  │   Layer    │──│   Engine   │──│  Manager   │  │   │
│  │  └────────────┘  └────────────┘  └────────────┘  │   │
│  │        │                               │          │   │
│  │        │              ┌────────────────┼────────┐ │   │
│  │        │              │                │        │ │   │
│  │  ┌─────┴─────┐  ┌────┴───┐  ┌────────┴──┐  ┌──┴┐│   │
│  │  │USB / WiFi │  │ Video  │  │   Audio   │  │  ││   │
│  │  │ Transport │  │Channel │  │  Channel  │  │  ││   │
│  │  └───────────┘  └────────┘  └───────────┘  │In││   │
│  │                       │           │         │pu││   │
│  │                  ┌────┴───┐  ┌────┴───┐    │t ││   │
│  │                  │Media   │  │Audio   │    │  ││   │
│  │                  │Project │  │Playback│    └──┘│   │
│  │                  │+Codec  │  │Capture │        │   │
│  │                  └────────┘  └────────┘        │   │
│  └──────────────────────────────────────────────────┘   │
│                                                          │
└──────────────────────────────────────────────────────────┘
          │ USB AOA / WiFi TCP │
          ▼                    ▼
┌─────────────────────────────────────────────────────────┐
│                   Car Head Unit                           │
└─────────────────────────────────────────────────────────┘
```

## Component Design

### 1. Transport Layer

Handles raw byte I/O over USB or WiFi.

```kotlin
interface Transport {
    suspend fun connect()
    suspend fun disconnect()
    suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int
    suspend fun write(buffer: ByteArray, offset: Int, length: Int)
    val isConnected: Boolean
}

class UsbAoaTransport(context: Context) : Transport
class WifiTransport(address: InetAddress, port: Int) : Transport
```

**USB AOA Flow:**
1. Phone receives USB_ACCESSORY_ATTACHED intent
2. Verify accessory model is "Android Auto" or "Android Open Automotive Protocol"
3. Open accessory file descriptor
4. Read/write via FileInputStream/FileOutputStream

**WiFi Flow:**
1. Bluetooth advertisement for head unit discovery
2. Head unit connects via WiFi to phone's TCP server
3. Read/write via Socket streams

### 2. Protocol Engine

Manages the AA protocol state machine.

```
┌─────────┐    ┌──────────┐    ┌─────────┐    ┌──────────┐
│  IDLE   │───▶│ VERSION  │───▶│   TLS   │───▶│ SERVICE  │
│         │    │  NEGO    │    │HANDSHAKE│    │DISCOVERY │
└─────────┘    └──────────┘    └─────────┘    └──────────┘
                                                     │
                                                     ▼
                                              ┌──────────┐
                                              │  ACTIVE  │
                                              │(channels)│
                                              └──────────┘
                                                     │
                                                     ▼
                                              ┌──────────┐
                                              │DISCONNECT│
                                              └──────────┘
```

**State transitions:**
- IDLE → VERSION_NEGO: Transport connected
- VERSION_NEGO → TLS: Version agreed (major=1, minor=7)
- TLS → SERVICE_DISCOVERY: TLS handshake complete
- SERVICE_DISCOVERY → ACTIVE: Services exchanged, channels opening
- ACTIVE → DISCONNECT: ByeBye or transport error

### 3. Message Framing

```
┌──────────────────────────────────────────┐
│            Frame Header (8 bytes)         │
├──────┬──────┬──────────┬─────────────────┤
│Chan  │Flags │ Length   │   Msg Type      │
│(2B)  │(1B)  │ (2B)    │   (2B + 1B)     │
├──────┴──────┴──────────┴─────────────────┤
│              Payload (protobuf)           │
└──────────────────────────────────────────┘

Flags:
  bit 0-1: Frame type (0=single, 1=first, 2=middle, 3=last)
  bit 2:   Encrypted
```

### 4. TLS Layer

```kotlin
class AaTlsServer {
    // Phone acts as TLS SERVER
    // Head unit is TLS CLIENT presenting a client certificate
    
    private val sslContext: SSLContext  // TLSv1.2
    private val keyPair: KeyPair       // RSA 2048, self-signed
    private val certificate: X509Certificate
    
    fun performHandshake(transport: Transport): SSLEngine
}
```

**Certificate generation:**
- On first launch, generate RSA 2048 key pair
- Create self-signed X.509 v1 certificate (matching what head units expect)
- Store in app's private KeyStore
- Accept any client certificate from head unit (no CA validation)

### 5. Channel Manager

Multiplexes logical channels over the single transport.

```kotlin
class ChannelManager(private val protocol: ProtocolEngine) {
    private val channels = ConcurrentHashMap<Int, Channel>()
    
    fun openChannel(id: Int, type: ChannelType): Channel
    fun closeChannel(id: Int)
    fun routeMessage(channelId: Int, message: ByteBuffer)
}

enum class ChannelType {
    CONTROL,      // Channel 0 — always open
    VIDEO,        // H.264/H.265 video stream
    AUDIO_MEDIA,  // Media audio (music, nav)
    AUDIO_SPEECH, // Speech audio (voice assistant)
    AUDIO_SYSTEM, // System sounds
    INPUT,        // Touch/key events
    SENSOR,       // GPS, night mode
    NAVIGATION    // Turn-by-turn data
}
```

### 6. Video Channel

```kotlin
class VideoChannel(
    private val context: Context,
    private val channelManager: ChannelManager
) {
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: MediaCodec? = null
    
    fun start(config: VideoConfig) {
        // 1. Request MediaProjection via activity result
        // 2. Create VirtualDisplay at negotiated resolution
        // 3. Configure MediaCodec encoder (H.264 BP)
        // 4. Feed encoded frames to channel
    }
}

data class VideoConfig(
    val width: Int,        // e.g. 1280
    val height: Int,       // e.g. 720
    val fps: Int,          // e.g. 30
    val codec: VideoCodec, // H264_BP, H265
    val dpi: Int           // e.g. 160
)
```

**Encoding pipeline:**
```
MediaProjection → VirtualDisplay (Surface) → MediaCodec (H.264 encoder) → ByteBuffer → Frame → Transport
```

### 7. Audio Channel

```kotlin
class AudioOutputChannel(private val context: Context) {
    private var playbackCapture: AudioRecord? = null
    
    fun start(config: AudioConfig) {
        // AudioPlaybackCapture API (Android 10+)
        // Captures all audio playing on the phone
        // Encodes as PCM 48kHz stereo or AAC-LC
        // Sends over audio channel
    }
}

class AudioInputChannel(private val context: Context) {
    private var audioTrack: AudioTrack? = null
    
    fun receiveAudio(data: ByteArray) {
        // Receives mic audio from head unit
        // Plays into virtual mic input (requires Accessibility or root)
        // Fallback: play through speaker for voice calls
    }
}
```

### 8. Input Channel

```kotlin
class InputChannel(private val context: Context) {
    fun onTouchEvent(x: Float, y: Float, action: Int, pointerId: Int) {
        // Option A: Accessibility Service (no root)
        //   - Can perform taps and gestures
        //   - Limited to predefined gesture types
        
        // Option B: InputManager.injectInputEvent (requires INJECT_EVENTS)
        //   - Full input injection
        //   - Requires system permission or Shizuku
        
        // Option C: Virtual display owns input
        //   - If we create the virtual display, we own its input
        //   - MediaProjection VirtualDisplay can receive input directly
    }
    
    fun onKeyEvent(keyCode: Int, action: Int) {
        // Dispatch as KeyEvent via Instrumentation or AccessibilityService
    }
}
```

**Recommended approach:** Use the VirtualDisplay's own input surface. Since we create the VirtualDisplay via MediaProjection, we can inject touch events directly into it without needing special permissions. This is the cleanest non-root solution.

### 9. Sensor Channel

```kotlin
class SensorChannel(private val context: Context) {
    fun provideSensors(): List<SensorType> {
        return listOf(
            SensorType.LOCATION,     // GPS from LocationManager
            SensorType.NIGHT_MODE,   // From UiModeManager
            SensorType.DRIVING_STATUS // Always "moving" when connected
        )
    }
    
    fun onSensorRequest(type: SensorType) {
        // Start providing periodic sensor updates
    }
}
```

---

## Sequence Diagrams

### Connection Establishment

```
Phone                                    Head Unit
  │                                          │
  │◄──── USB AOA Connect ───────────────────│
  │                                          │
  │──── VERSION_REQUEST (v1.7) ────────────▶│
  │◄──── VERSION_RESPONSE (v1.7) ──────────│
  │                                          │
  │◄──── TLS ClientHello ──────────────────│
  │──── TLS ServerHello + Cert ────────────▶│
  │◄──── TLS ClientCert + Finished ────────│
  │──── TLS Finished ──────────────────────▶│
  │                                          │
  │──── AUTH_COMPLETE ─────────────────────▶│
  │                                          │
  │──── SERVICE_DISCOVERY_RESPONSE ────────▶│
  │◄──── SERVICE_DISCOVERY_REQUEST ────────│
  │                                          │
  │◄──── CHANNEL_OPEN_REQUEST (video) ─────│
  │──── CHANNEL_OPEN_RESPONSE ─────────────▶│
  │                                          │
  │──── MEDIA_SETUP (H.264 config) ────────▶│
  │──── MEDIA_START ───────────────────────▶│
  │──── MEDIA_DATA (video frames) ─────────▶│
  │◄──── INPUT (touch events) ─────────────│
  │                                          │
```

### Video Frame Flow

```
Screen Content
      │
      ▼
┌─────────────┐
│VirtualDisplay│  (renders at HU resolution)
└──────┬──────┘
       │ Surface
       ▼
┌─────────────┐
│  MediaCodec │  (H.264 encoder, hardware-accelerated)
│  (encoder)  │
└──────┬──────┘
       │ ByteBuffer (NAL units)
       ▼
┌─────────────┐
│   Framing   │  (split into AA frames, add headers)
└──────┬──────┘
       │
       ▼
┌─────────────┐
│  Transport  │  (USB bulk transfer / TCP socket)
└─────────────┘
```

---

## Technology Choices

| Component | Choice | Rationale |
|-----------|--------|-----------|
| Language | Kotlin | Modern Android, coroutines for async |
| Async | Kotlin Coroutines + Flow | Native, lightweight, structured concurrency |
| Protobuf | Wire (Square) or protobuf-lite | Lightweight, Kotlin-friendly |
| TLS | Java SSLEngine | No native deps, works on all devices |
| Video encode | MediaCodec (hardware) | Low latency, low CPU |
| Audio capture | AudioPlaybackCapture | Standard API, Android 10+ |
| USB | Android USB Accessory API | Standard, no root needed |
| Bluetooth | Android Bluetooth API | For wireless discovery |
| DI | Manual / Koin | Keep it simple |
| Build | Gradle + Kotlin DSL | Standard Android |

---

## Project Structure

```
app/
├── src/main/kotlin/org/openandroidauto/
│   ├── MainActivity.kt              # Permission grants, UI
│   ├── ProjectionService.kt         # Foreground service lifecycle
│   ├── transport/
│   │   ├── Transport.kt             # Interface
│   │   ├── UsbAoaTransport.kt       # USB implementation
│   │   └── WifiTransport.kt         # WiFi implementation
│   ├── protocol/
│   │   ├── ProtocolEngine.kt        # State machine
│   │   ├── MessageFramer.kt         # Frame encode/decode
│   │   ├── TlsServer.kt            # TLS handshake
│   │   └── Messages.kt             # Message type constants
│   ├── channel/
│   │   ├── ChannelManager.kt        # Channel multiplexer
│   │   ├── VideoChannel.kt          # Video capture + encode
│   │   ├── AudioOutputChannel.kt    # Audio capture
│   │   ├── AudioInputChannel.kt     # Mic from HU
│   │   ├── InputChannel.kt          # Touch/key injection
│   │   └── SensorChannel.kt         # GPS, night mode
│   └── proto/                        # Generated protobuf classes
├── src/main/proto/                   # .proto definitions
└── src/main/AndroidManifest.xml
```

---

## Error Handling

| Scenario | Response |
|----------|----------|
| USB disconnect during session | Release all resources, stop service |
| MediaCodec error | Attempt codec restart, fall back to software encoder |
| TLS handshake failure | Show notification, allow retry |
| Audio capture denied | Continue without audio, notify user |
| MediaProjection denied | Cannot proceed, show explanation |
| Frame too large | Fragment into multiple AA frames |
| Head unit timeout (no ping) | Send ping, disconnect after 10s |

---

## Security Considerations

- TLS certificate private key stored in Android Keystore (hardware-backed where available)
- No secrets hardcoded in APK
- MediaProjection requires explicit user consent each session
- No data sent to external servers
- All communication is local (USB or local WiFi)

---

## Phased Implementation

### Phase 1: USB Wired + Video Only
- USB AOA transport
- Protocol engine (version, TLS, service discovery)
- Video channel (MediaProjection → H.264 → frames)
- Basic touch input (tap only)

### Phase 2: Full Input + Audio
- Multi-touch support
- Key events (back, home, media)
- Audio output capture
- Microphone input routing

### Phase 3: Wireless + Polish
- Bluetooth advertisement
- WiFi transport
- Sensor channel (GPS, night mode)
- Connection reliability and reconnection
- Settings UI

---

## Dependencies on External Protocol Definitions

We will use the protobuf definitions from `uglyoldbob/android-auto` (LGPL-3.0) as our starting point:
- `protobuf/Wifi.proto` — main protocol messages
- `protobuf/Bluetooth.proto` — wireless handoff messages

These define all message types, channel structures, and configuration formats needed for the protocol implementation.
