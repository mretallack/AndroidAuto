# Tasks: Android Auto Phone-Side Projection

## Phase 1: USB Wired + Video Only

### 1.1 Project Setup
- [ ] Initialize Android project with Gradle Kotlin DSL (minSdk 32, targetSdk 35)
- [ ] Add dependencies: protobuf-lite, kotlinx-coroutines, Wire/protobuf
- [ ] Copy `Wifi.proto` and `Bluetooth.proto` from uglyoldbob/android-auto, configure protobuf code generation
- [ ] Create package structure: `org.openandroidauto.{transport,protocol,channel,proto}`
- [ ] Create `AndroidManifest.xml` with USB accessory intent filter and required permissions

### 1.2 Transport Layer
- [ ] Define `Transport` interface (connect, disconnect, read, write, isConnected)
- [ ] Implement `UsbAoaTransport` — detect USB accessory with model "Android Auto", open file descriptor, async read/write
- [ ] Add USB accessory intent receiver in `MainActivity` to trigger connection on plug-in
- [ ] Write unit tests for transport interface contract with a `MockTransport`

### 1.3 Message Framing
- [ ] Implement `FrameHeader` data class (channelId: UByte, flags: UByte)
- [ ] Implement `MessageFramer.encode()` — serialize frame header + length + payload, handle fragmentation at 16KB boundary
- [ ] Implement `MessageFramer.decode()` — read header, length, payload; reassemble multi-frame messages
- [ ] Write unit tests: single frame round-trip, multi-frame fragmentation/reassembly, max payload boundary

### 1.4 TLS Layer
- [ ] Implement `AaTlsServer` — generate RSA 2048 self-signed X.509 v1 cert on first launch, store in Android Keystore
- [ ] Implement TLS handshake using `SSLEngine` in server mode (TLSv1.2), accept any client certificate
- [ ] Wrap transport read/write with TLS encryption/decryption after handshake
- [ ] Write unit tests: cert generation, handshake with a mock TLS client

### 1.5 Protocol Engine
- [ ] Define protocol states enum: IDLE, VERSION_NEGOTIATION, TLS_HANDSHAKE, SERVICE_DISCOVERY, ACTIVE, DISCONNECTED
- [ ] Implement VERSION_REQUEST send and VERSION_RESPONSE receive (version 1.1)
- [ ] Implement SSL_HANDSHAKE message relay (encapsulate TLS data in protocol frames)
- [ ] Implement AUTH_COMPLETE send after TLS completes
- [ ] Implement SERVICE_DISCOVERY_REQUEST receive and SERVICE_DISCOVERY_RESPONSE send (advertise video, audio, input, sensor channels)
- [ ] Implement CHANNEL_OPEN_REQUEST receive and CHANNEL_OPEN_RESPONSE send
- [ ] Implement PING_REQUEST/RESPONSE handling
- [ ] Implement SHUTDOWN_REQUEST/RESPONSE handling
- [ ] Write unit tests: state transitions, invalid state rejection, message routing

### 1.6 Video Channel
- [ ] Implement `VideoChannel` — request MediaProjection via Activity result API
- [ ] Create VirtualDisplay at negotiated resolution (default 720p 30fps)
- [ ] Configure MediaCodec H.264 Baseline Profile encoder with VirtualDisplay surface as input
- [ ] Read encoded NAL units from MediaCodec output, package as AV_MEDIA_WITH_TIMESTAMP_INDICATION
- [ ] Handle VIDEO_FOCUS_REQUEST from head unit, respond with VIDEO_FOCUS_INDICATION
- [ ] Handle AV_CHANNEL_SETUP_REQUEST, respond with SETUP_RESPONSE (max_unacked, config index)
- [ ] Handle START_INDICATION / STOP_INDICATION to control encoding
- [ ] Write unit tests: config negotiation, frame packaging with timestamps

### 1.7 Input Channel (Basic)
- [ ] Implement `InputChannel` — receive INPUT_EVENT_INDICATION messages
- [ ] Parse TouchEvent (x, y, pointer_id, action: PRESS/RELEASE/DRAG)
- [ ] Inject touch events into VirtualDisplay via `Instrumentation` or dispatch to the projected surface
- [ ] Handle BINDING_REQUEST, respond with BINDING_RESPONSE
- [ ] Write unit tests: coordinate mapping, touch action translation

### 1.8 Foreground Service
- [ ] Create `ProjectionService` as a foreground service with persistent notification
- [ ] Manage lifecycle: start on USB connect, stop on disconnect
- [ ] Hold WakeLock during active projection
- [ ] Wire together Transport → ProtocolEngine → ChannelManager → VideoChannel + InputChannel

### 1.9 Integration Testing (Phase 1)
- [ ] Create `docker-compose.test.yml` with uglyoldbob/android-auto head unit emulator
- [ ] Write Dockerfile for the emulator (Rust build with wireless feature)
- [ ] Write integration test: TCP connect → version handshake → TLS → service discovery
- [ ] Write integration test: open video channel → send test H.264 frame → receive ACK
- [ ] Write integration test: receive touch event from emulator → verify coordinates

---

## Phase 2: Full Input + Audio

### 2.1 Multi-touch Input
- [ ] Support multiple pointer IDs in touch events
- [ ] Handle POINTER_DOWN / POINTER_UP actions for multi-touch gestures
- [ ] Map touch coordinates from head unit resolution to phone screen resolution

### 2.2 Key Events
- [ ] Parse ButtonEvent messages (scan_code, is_pressed, meta, long_press)
- [ ] Map AA keycodes to Android KeyEvent codes (BACK, HOME, MEDIA_PLAY, MEDIA_NEXT, etc.)
- [ ] Dispatch key events via AccessibilityService or Instrumentation

### 2.3 Audio Output Channel
- [ ] Implement `AudioOutputChannel` using AudioPlaybackCapture API
- [ ] Configure AudioRecord with 48kHz stereo PCM
- [ ] Package audio samples as AV_MEDIA_WITH_TIMESTAMP_INDICATION on media audio channel
- [ ] Handle SETUP_REQUEST/START/STOP for audio channel
- [ ] Handle AUDIO_FOCUS_REQUEST/RESPONSE

### 2.4 Audio Input Channel (Microphone)
- [ ] Implement `AudioInputChannel` — receive audio data from head unit (mic)
- [ ] Handle AV_INPUT_OPEN_REQUEST, respond with AV_INPUT_OPEN_RESPONSE
- [ ] Route received audio to phone's audio system (AudioTrack or virtual mic if available)
- [ ] Handle AV_MEDIA_ACK_INDICATION for flow control

### 2.5 Integration Testing (Phase 2)
- [ ] Write integration test: multi-touch sequence (pinch zoom)
- [ ] Write integration test: key event dispatch
- [ ] Write integration test: audio channel open → send PCM → receive ACK
- [ ] Write integration test: mic channel open → receive audio from emulator

---

## Phase 3: Wireless + Polish

### 3.1 Bluetooth Discovery
- [ ] Implement Bluetooth RFCOMM server (UUID: AndroidAuto, channel 22)
- [ ] Handle BLUETOOTH_SOCKET_INFO_REQUEST — respond with phone's WiFi IP and port
- [ ] Handle BLUETOOTH_NETWORK_INFO_REQUEST — respond with WiFi network details

### 3.2 WiFi Transport
- [ ] Implement `WifiTransport` — TCP server listening on port 5277
- [ ] Accept connection from head unit after Bluetooth handoff
- [ ] Reuse same ProtocolEngine/ChannelManager as USB path

### 3.3 Sensor Channel
- [ ] Implement `SensorChannel` — advertise LOCATION, NIGHT_MODE, DRIVING_STATUS
- [ ] Handle SENSOR_START_REQUEST, respond with SENSOR_START_RESPONSE
- [ ] Send periodic SENSOR_EVENT_INDICATION with GPS data from LocationManager
- [ ] Send NightMode based on UiModeManager or time-of-day

### 3.4 Navigation Channel
- [ ] Implement `NavigationChannel` — receive turn-by-turn data if nav app provides it
- [ ] Forward NavigationTurnEvent, NavigationDistanceEvent, NavigationStatus to head unit

### 3.5 Settings & UI
- [ ] Create settings screen: video resolution, FPS, codec preference
- [ ] Show connection status, latency, frame rate in notification
- [ ] Add "trusted devices" management (remember paired head units)
- [ ] Handle MediaProjection permission grant flow cleanly

### 3.6 Reliability
- [ ] Implement reconnection logic (retry on transport error)
- [ ] Handle WiFi interruptions gracefully (buffer, resume)
- [ ] Watchdog: detect stalled connection (no ping response in 10s), force disconnect
- [ ] Resource cleanup on all error paths (no leaked MediaCodec/MediaProjection)

### 3.7 End-to-End Testing
- [ ] Test with headunit-revived on a real Android tablet over USB
- [ ] Test with headunit-revived over WiFi
- [ ] Test with a real car head unit (if available)
- [ ] Performance profiling: CPU, memory, latency measurements
- [ ] Battery drain measurement during 1-hour session

### 3.8 CI/CD
- [ ] GitHub Actions: build APK on push
- [ ] GitHub Actions: run unit tests
- [ ] GitHub Actions: run integration tests with Docker head unit emulator
- [ ] Create release workflow: signed APK, changelog
