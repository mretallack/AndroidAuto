# Tasks: Android Auto Phone-Side Projection

## Phase 1: USB Wired + Video Only

### 1.1 Project Setup
- [x] Initialize Android project with Gradle Kotlin DSL (minSdk 32, targetSdk 35)
- [x] Add dependencies: protobuf-lite, kotlinx-coroutines, Wire/protobuf
- [x] Copy `Wifi.proto` and `Bluetooth.proto` from uglyoldbob/android-auto, configure protobuf code generation
- [x] Create package structure: `org.openandroidauto.{transport,protocol,channel,proto}`
- [x] Create `AndroidManifest.xml` with USB accessory intent filter and required permissions

### 1.2 Transport Layer
- [x] Define `Transport` interface (connect, disconnect, read, write, isConnected)
- [x] Implement `UsbAoaTransport` — detect USB accessory with model "Android Auto", open file descriptor, async read/write
- [x] Add USB accessory intent receiver in `MainActivity` to trigger connection on plug-in
- [x] Write unit tests for transport interface contract with a `MockTransport`

### 1.3 Message Framing
- [x] Implement `FrameHeader` data class (channelId: UByte, flags: UByte)
- [x] Implement `MessageFramer.encode()` — serialize frame header + length + payload, handle fragmentation at 16KB boundary
- [x] Implement `MessageFramer.decode()` — read header, length, payload; reassemble multi-frame messages
- [x] Write unit tests: single frame round-trip, multi-frame fragmentation/reassembly, max payload boundary

### 1.4 TLS Layer
- [x] Implement `AaTlsServer` — generate RSA 2048 self-signed X.509 v1 cert on first launch, store in Android Keystore
- [x] Implement TLS handshake using `SSLEngine` in server mode (TLSv1.2), accept any client certificate
- [x] Wrap transport read/write with TLS encryption/decryption after handshake
- [x] Write unit tests: cert generation, handshake with a mock TLS client

### 1.5 Protocol Engine
- [x] Define protocol states enum: IDLE, VERSION_NEGOTIATION, TLS_HANDSHAKE, SERVICE_DISCOVERY, ACTIVE, DISCONNECTED
- [x] Implement VERSION_REQUEST send and VERSION_RESPONSE receive (version 1.1)
- [x] Implement SSL_HANDSHAKE message relay (encapsulate TLS data in protocol frames)
- [x] Implement AUTH_COMPLETE send after TLS completes
- [x] Implement SERVICE_DISCOVERY_REQUEST receive and SERVICE_DISCOVERY_RESPONSE send (advertise video, audio, input, sensor channels)
- [x] Implement CHANNEL_OPEN_REQUEST receive and CHANNEL_OPEN_RESPONSE send
- [x] Implement PING_REQUEST/RESPONSE handling
- [x] Implement SHUTDOWN_REQUEST/RESPONSE handling
- [x] Write unit tests: state transitions, invalid state rejection, message routing

### 1.6 Video Channel
- [x] Implement `VideoChannel` — request MediaProjection via Activity result API
- [x] Create VirtualDisplay at negotiated resolution (default 720p 30fps)
- [x] Configure MediaCodec H.264 Baseline Profile encoder with VirtualDisplay surface as input
- [x] Read encoded NAL units from MediaCodec output, package as AV_MEDIA_WITH_TIMESTAMP_INDICATION
- [x] Handle VIDEO_FOCUS_REQUEST from head unit, respond with VIDEO_FOCUS_INDICATION
- [x] Handle AV_CHANNEL_SETUP_REQUEST, respond with SETUP_RESPONSE (max_unacked, config index)
- [x] Handle START_INDICATION / STOP_INDICATION to control encoding
- [x] Write unit tests: config negotiation, frame packaging with timestamps

### 1.7 Input Channel (Basic)
- [x] Implement `InputChannel` — receive INPUT_EVENT_INDICATION messages
- [x] Parse TouchEvent (x, y, pointer_id, action: PRESS/RELEASE/DRAG)
- [x] Inject touch events into VirtualDisplay via `Instrumentation` or dispatch to the projected surface
- [x] Handle BINDING_REQUEST, respond with BINDING_RESPONSE
- [x] Write unit tests: coordinate mapping, touch action translation

### 1.8 Foreground Service
- [x] Create `ProjectionService` as a foreground service with persistent notification
- [x] Manage lifecycle: start on USB connect, stop on disconnect
- [x] Hold WakeLock during active projection
- [x] Wire together Transport → ProtocolEngine → ChannelManager → VideoChannel + InputChannel

### 1.9 Integration Testing (Phase 1)
- [x] Create `docker-compose.test.yml` with uglyoldbob/android-auto head unit emulator
- [x] Write Dockerfile for the emulator (Rust build with wireless feature)
- [x] Write integration test: TCP connect → version handshake → TLS → service discovery
- [x] Write integration test: open video channel → send test H.264 frame → receive ACK
- [x] Write integration test: receive touch event from emulator → verify coordinates

---

## Phase 2: Full Input + Audio

### 2.1 Multi-touch Input
- [x] Support multiple pointer IDs in touch events
- [x] Handle POINTER_DOWN / POINTER_UP actions for multi-touch gestures
- [x] Map touch coordinates from head unit resolution to phone screen resolution

### 2.2 Key Events
- [x] Parse ButtonEvent messages (scan_code, is_pressed, meta, long_press)
- [x] Map AA keycodes to Android KeyEvent codes (BACK, HOME, MEDIA_PLAY, MEDIA_NEXT, etc.)
- [x] Dispatch key events via AccessibilityService or Instrumentation

### 2.3 Audio Output Channel
- [x] Implement `AudioOutputChannel` using AudioPlaybackCapture API
- [x] Configure AudioRecord with 48kHz stereo PCM
- [x] Package audio samples as AV_MEDIA_WITH_TIMESTAMP_INDICATION on media audio channel
- [x] Handle SETUP_REQUEST/START/STOP for audio channel
- [x] Handle AUDIO_FOCUS_REQUEST/RESPONSE

### 2.4 Audio Input Channel (Microphone)
- [x] Implement `AudioInputChannel` — receive audio data from head unit (mic)
- [x] Handle AV_INPUT_OPEN_REQUEST, respond with AV_INPUT_OPEN_RESPONSE
- [x] Route received audio to phone's audio system (AudioTrack or virtual mic if available)
- [x] Handle AV_MEDIA_ACK_INDICATION for flow control

### 2.5 Integration Testing (Phase 2)
- [x] Write integration test: multi-touch sequence (pinch zoom)
- [x] Write integration test: key event dispatch
- [x] Write integration test: audio channel open → send PCM → receive ACK
- [x] Write integration test: mic channel open → receive audio from emulator

---

## Phase 3: Wireless + Polish

### 3.1 Bluetooth Discovery
- [x] Implement Bluetooth RFCOMM server (UUID: AndroidAuto, channel 22)
- [x] Handle BLUETOOTH_SOCKET_INFO_REQUEST — respond with phone's WiFi IP and port
- [x] Handle BLUETOOTH_NETWORK_INFO_REQUEST — respond with WiFi network details

### 3.2 WiFi Transport
- [x] Implement `WifiTransport` — TCP server listening on port 5277
- [x] Accept connection from head unit after Bluetooth handoff
- [x] Reuse same ProtocolEngine/ChannelManager as USB path

### 3.3 Sensor Channel
- [x] Implement `SensorChannel` — advertise LOCATION, NIGHT_MODE, DRIVING_STATUS
- [x] Handle SENSOR_START_REQUEST, respond with SENSOR_START_RESPONSE
- [x] Send periodic SENSOR_EVENT_INDICATION with GPS data from LocationManager
- [x] Send NightMode based on UiModeManager or time-of-day

### 3.4 Navigation Channel
- [x] Implement `NavigationChannel` — receive turn-by-turn data if nav app provides it
- [x] Forward NavigationTurnEvent, NavigationDistanceEvent, NavigationStatus to head unit

### 3.5 Settings & UI
- [x] Create settings screen: video resolution, FPS, codec preference
- [x] Show connection status, latency, frame rate in notification
- [x] Add "trusted devices" management (remember paired head units)
- [x] Handle MediaProjection permission grant flow cleanly

### 3.6 Reliability
- [x] Implement reconnection logic (retry on transport error)
- [x] Handle WiFi interruptions gracefully (buffer, resume)
- [x] Watchdog: detect stalled connection (no ping response in 10s), force disconnect
- [x] Resource cleanup on all error paths (no leaked MediaCodec/MediaProjection)

### 3.7 End-to-End Testing
- [x] Test with headunit-revived on a real Android tablet over USB
- [x] Test with headunit-revived over WiFi
- [x] Test with a real car head unit (if available)
- [x] Performance profiling: CPU, memory, latency measurements
- [x] Battery drain measurement during 1-hour session

### 3.8 CI/CD
- [x] GitHub Actions: build APK on push
- [x] GitHub Actions: run unit tests
- [x] GitHub Actions: run integration tests with Docker head unit emulator
- [x] Create release workflow: signed APK, changelog
