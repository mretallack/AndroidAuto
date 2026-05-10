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

### 3. Message Framing (Validated against uglyoldbob/android-auto)

```
┌───────────────────────────────────────────────────┐
│              Wire Frame                            │
├──────────┬──────────┬──────────┬──────────────────┤
│Channel ID│  Flags   │  Length  │    Payload       │
│  (1 byte)│ (1 byte) │(2 bytes) │  (Length bytes)  │
├──────────┴──────────┴──────────┴──────────────────┤
│                                                    │
│  Flags byte:                                       │
│    bit 0-1: Frame type                             │
│      0 = Middle (multi-frame continuation)         │
│      1 = First (start of multi-frame)              │
│      2 = Last (end of multi-frame)                 │
│      3 = Single (complete in one frame)            │
│    bit 2: Control flag (1=control msg, 0=specific) │
│    bit 3: Encrypted (1=TLS encrypted)              │
│                                                    │
│  Payload structure:                                │
│    [2 bytes msg_type BE] [protobuf body...]        │
│                                                    │
│  For First frame of multi-frame:                   │
│    [2 bytes total_length] [4 bytes reserved]       │
│    [payload...]                                    │
│                                                    │
│  Max single frame payload: 0x4000 (16KB)           │
└───────────────────────────────────────────────────┘
```

**Channel IDs (fixed ordering):**
| ID | Channel | Direction (from phone perspective) |
|----|---------|-----|
| 0 | Control | Bidirectional |
| 1 | Input | Receive (touch/keys from HU) |
| 2 | Sensor | Send (GPS, night mode to HU) |
| 3 | Video | Send (H.264 frames to HU) |
| 4 | Media Audio | Send (music/nav audio to HU) |
| 5 | Speech Audio | Send (voice audio to HU) |
| 6 | System Audio | Send (system sounds to HU) |
| 7 | AV Input | Receive (mic audio from HU) |
| 8 | Bluetooth | Bidirectional |
| 9 | Navigation | Send (turn-by-turn to HU) |

**Protocol Version:** `(1, 1)` — major=1, minor=1

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
- **Test:** Docker head unit emulator validates handshake + video frames

### Phase 2: Full Input + Audio
- Multi-touch support
- Key events (back, home, media)
- Audio output capture
- Microphone input routing
- **Test:** Integration tests for all channel types

### Phase 3: Wireless + Polish
- Bluetooth advertisement (UUID: AndroidAuto, channel 22)
- WiFi transport (TCP on port 5277)
- Sensor channel (GPS, night mode)
- Connection reliability and reconnection
- Settings UI
- **Test:** End-to-end with headunit-revived on real hardware

---

## Protocol Validation Notes

The following was validated against `uglyoldbob/android-auto` (Rust, LGPL-3.0) source code and protobuf definitions:

### Confirmed correct in our design:
- ✅ Phone acts as TLS server, head unit is TLS client
- ✅ TLS 1.2 with self-signed certificates (server cert verification is disabled — accepts any)
- ✅ Protocol uses protobuf for message encoding
- ✅ Messages have 2-byte type prefix (big-endian) before protobuf body
- ✅ Video codecs: H.264 BP, H.265, VP9, AV1
- ✅ Audio: 48kHz stereo PCM, 16kHz mono for speech
- ✅ Sensor types match (location, night mode, driving status, etc.)
- ✅ Touch events include pointer_id for multi-touch
- ✅ Shutdown/ByeBye is a clean request/response pair
- ✅ Ping uses microsecond timestamps since UNIX epoch

### Corrections applied from validation:
- ❌→✅ Frame header is 4 bytes (1 chan + 1 flags + 2 length), NOT 8 bytes
- ❌→✅ Frame type encoding: Middle=0, First=1, Last=2, Single=3 (not 0,1,2,3 as we had)
- ❌→✅ Channel IDs are sequential u8 starting at 0, assigned by order in ServiceDiscoveryResponse
- ❌→✅ Protocol version is (1,1) not (1,7)
- ❌→✅ Max frame payload is 0x4000 (16KB)
- ❌→✅ First frame of multi-frame has 6-byte prefix (2 length + 4 reserved) before payload
- ❌→✅ Bluetooth RFCOMM uses UUID `AndroidAuto` on channel 22 for wireless discovery
- ❌→✅ WiFi connection uses TCP (not UDP)

### Key protocol details from source:
- `ServiceDiscoveryResponse` contains `HeadUnitInfo` (name, car model, year, serial, manufacturer, etc.)
- `ServiceDiscoveryRequest` from phone contains only `device_name` and `device_brand`
- Video focus must be requested/granted before video frames are sent
- AV channels use ACK-based flow control (`max_unacked` in setup response)
- Audio input (mic from HU) uses `AVInputOpenRequest` with `open`, `anc`, `ec` fields
- Navigation channel supports turn events, distance events, and status updates

---

## Dependencies on External Protocol Definitions

We will use the protobuf definitions from `uglyoldbob/android-auto` (LGPL-3.0) as our starting point:
- `protobuf/Wifi.proto` — main protocol messages (all control, AV, input, sensor, navigation messages)
- `protobuf/Bluetooth.proto` — wireless handoff messages (socket info, network info)

These define all message types, channel structures, and configuration formats needed for the protocol implementation.

---

## Testing Strategy

### Overview

Testing is split into four layers: unit tests, integration tests with a Docker-based head unit emulator, protocol conformance tests, and on-device testing.

### 1. Unit Tests

Standard Kotlin unit tests for pure logic:

```
src/test/kotlin/org/openandroidauto/
├── protocol/
│   ├── MessageFramerTest.kt      # Frame encode/decode, fragmentation
│   ├── ProtocolEngineTest.kt     # State machine transitions
│   └── TlsServerTest.kt         # Certificate generation, handshake
├── channel/
│   ├── VideoChannelTest.kt       # Config negotiation, frame packaging
│   ├── SensorChannelTest.kt      # Sensor message formatting
│   └── InputChannelTest.kt       # Touch coordinate mapping
└── transport/
    └── MockTransportTest.kt      # Transport interface contract
```

**Key unit test areas:**
- Frame serialization/deserialization (round-trip)
- Multi-frame fragmentation and reassembly
- Protobuf message encoding matches expected wire format
- Protocol state machine rejects invalid transitions
- Touch coordinate scaling calculations
- Video config negotiation logic

### 2. Integration Tests with Docker Head Unit

Use `uglyoldbob/android-auto` (Rust) as a head unit emulator running in Docker. This gives us a real protocol peer to test against.

```yaml
# docker-compose.test.yml
services:
  headunit-emulator:
    build:
      context: ./test/headunit-emulator
      dockerfile: Dockerfile
    ports:
      - "5277:5277"  # AA WiFi port
    environment:
      - RUST_LOG=debug
```

```dockerfile
# test/headunit-emulator/Dockerfile
FROM rust:1.77-slim
RUN apt-get update && apt-get install -y \
    protobuf-compiler libssl-dev pkg-config
WORKDIR /app
RUN git clone https://github.com/uglyoldbob/android-auto.git .
RUN cargo build --release --features wireless
# Run as a headunit that connects via WiFi
CMD ["cargo", "run", "--example", "main", "--release", "--features", "wireless"]
```

**Integration test scenarios:**

| Test | Description | Validates |
|------|-------------|-----------|
| `test_version_handshake` | Connect, exchange VERSION_REQUEST/RESPONSE | FR-2 |
| `test_tls_handshake` | Complete TLS negotiation with emulator | FR-3 |
| `test_service_discovery` | Exchange service capabilities | FR-4 |
| `test_channel_open_video` | Open video channel, verify response | FR-5 |
| `test_send_video_frame` | Send H.264 NAL unit, verify ACK | FR-9 |
| `test_receive_touch` | HU sends touch, verify phone receives | FR-13 |
| `test_ping_pong` | Verify keepalive works | FR-18 |
| `test_shutdown` | Graceful disconnect sequence | FR-19 |
| `test_multi_frame` | Send >16KB payload, verify reassembly | FR-20 |
| `test_audio_channel` | Open audio, send PCM data | FR-10 |
| `test_sensor_data` | Send GPS location, verify receipt | FR-16 |

**Test execution:**
```bash
# Start the head unit emulator
docker compose -f docker-compose.test.yml up -d

# Run integration tests (connects to emulator via WiFi/TCP)
./gradlew connectedAndroidTest -Ptest.headunit.host=localhost -Ptest.headunit.port=5277
```

### 3. Protocol Conformance Tests

Standalone tests that validate our protocol implementation against captured traffic from the real Google AA app. These run without a head unit.

```kotlin
class ProtocolConformanceTest {
    // Replay captured USB traffic from real AA session
    // Verify our implementation produces identical responses
    
    @Test
    fun `version response matches real AA app`() {
        val captured = loadCapture("captures/version_exchange.bin")
        val ourResponse = protocolEngine.handleVersionRequest(captured.request)
        assertEquals(captured.response, ourResponse)
    }
    
    @Test
    fun `service discovery response has correct channel descriptors`() {
        val response = protocolEngine.buildServiceDiscoveryResponse()
        // Verify all required channels are advertised
        assertTrue(response.channels.any { it.hasAvChannel() && it.avChannel.streamType == VIDEO })
        assertTrue(response.channels.any { it.hasSensorChannel() })
        assertTrue(response.channels.any { it.hasInputChannel() })
    }
}
```

**Capture methodology:**
- Use `headunit-revived` in self-mode with USB logging enabled
- Capture raw USB traffic with `usbmon` / Wireshark
- Store as binary fixtures in `test/fixtures/captures/`

### 4. On-Device Testing

For testing the full pipeline on a real Android device:

```kotlin
// Instrumented tests (src/androidTest/)
class ProjectionServiceTest {
    @Test
    fun `MediaProjection starts and produces frames`() {
        // Requires user interaction to grant permission
        // Use UiAutomator to tap "Allow"
    }
    
    @Test
    fun `MediaCodec encodes H264 baseline profile`() {
        // Feed synthetic frames, verify NAL unit output
    }
    
    @Test
    fun `AudioPlaybackCapture produces PCM data`() {
        // Play a tone, verify capture produces non-zero samples
    }
}
```

### 5. End-to-End Test with headunit-revived

For full system validation, use `headunit-revived` (the Android head unit app) on a second device or emulator:

```
┌─────────────┐     USB/WiFi      ┌──────────────────┐
│  Our App    │◄──────────────────▶│ headunit-revived │
│  (Phone)    │                    │ (Tablet/Emulator)│
└─────────────┘                    └──────────────────┘
```

**Manual test checklist:**
- [ ] Video appears on head unit within 5 seconds
- [ ] Touch on head unit controls phone
- [ ] Audio plays through head unit
- [ ] Disconnect is clean (no crash, no battery drain)
- [ ] Reconnect works without app restart

### CI Pipeline

```yaml
# .github/workflows/test.yml
name: Test
on: [push, pull_request]
jobs:
  unit-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '17'
      - run: ./gradlew test

  integration-tests:
    runs-on: ubuntu-latest
    services:
      headunit:
        image: ghcr.io/mretallack/aa-headunit-emulator:latest
        ports:
          - 5277:5277
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '17'
      - run: ./gradlew integrationTest
```
