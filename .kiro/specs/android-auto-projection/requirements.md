# Requirements: Android Auto Phone-Side Projection

## Overview

An open-source Android app that replaces Google's `com.google.android.projection.gearhead` — running on the phone and projecting to a car's head unit over USB or WiFi.

## Target Users

- Users running degoogled Android (LineageOS, GrapheneOS) without Google Play Services
- Privacy-conscious users who want an open-source alternative
- Developers building custom automotive experiences

## Constraints

- Must work without root on LineageOS/stock Android
- Must use standard Android APIs (MediaProjection, MediaCodec, USB Accessory)
- No Google Play Services dependency
- Minimum Android 12 (API 32) — matches current AA requirements

---

## User Stories

### US-1: USB Wired Connection

**As a** driver with a USB cable connected to my car's head unit,
**I want** my phone to automatically project to the car's screen,
**So that** I can use navigation and media on the car display without Google's app.

**Acceptance Criteria:**
- Phone detects USB connection to an AA-compatible head unit
- TLS handshake completes successfully
- Video stream appears on head unit within 5 seconds of connection
- Touch events from head unit are received and processed

### US-2: Screen Projection

**As a** user connected to a head unit,
**I want** my phone screen to be captured and displayed on the car screen,
**So that** I can see and interact with my phone apps on the larger display.

**Acceptance Criteria:**
- MediaProjection captures the phone screen
- Video is encoded as H.264 at minimum 720p 30fps
- Latency between phone action and head unit display is under 200ms
- User grants MediaProjection permission once per session

### US-3: Touch Input

**As a** user viewing my phone on the car screen,
**I want** to tap and swipe on the car's touchscreen,
**So that** I can control my phone apps from the car display.

**Acceptance Criteria:**
- Touch events from head unit are translated to Android input events
- Multi-touch is supported (at least 2 points)
- Touch coordinates are correctly mapped to phone screen resolution

### US-4: Audio Routing

**As a** user playing music or navigation audio,
**I want** audio from my phone to play through the car speakers,
**So that** I can hear directions and music clearly.

**Acceptance Criteria:**
- Phone audio is captured and streamed to head unit
- Audio format is 48kHz stereo PCM or AAC-LC
- Audio latency is under 100ms
- Microphone input from car is routed back to phone for calls/assistant

### US-5: WiFi Wireless Connection

**As a** driver who doesn't want to plug in a cable,
**I want** to connect wirelessly to my car's head unit,
**So that** I can project without physical connection.

**Acceptance Criteria:**
- Phone advertises via Bluetooth for head unit discovery
- WiFi Direct or local network connection is established
- Video/audio streaming works over WiFi with acceptable quality
- Connection survives brief WiFi interruptions

### US-6: Graceful Disconnect

**As a** user finishing a drive,
**I want** the projection to stop cleanly when I disconnect,
**So that** my phone returns to normal operation without issues.

**Acceptance Criteria:**
- USB disconnect triggers clean shutdown
- ByeBye protocol message is sent/received
- All resources (MediaProjection, MediaCodec, sockets) are released
- No battery drain after disconnect

---

## Functional Requirements (EARS Notation)

### Connection & Transport

**FR-1:** WHEN a USB cable is connected to an AA-compatible head unit, THE SYSTEM SHALL detect the USB AOA accessory and initiate the Android Auto protocol handshake.

**FR-2:** WHEN the protocol handshake begins, THE SYSTEM SHALL send a VERSION_REQUEST message and negotiate a compatible protocol version.

**FR-3:** WHEN version negotiation succeeds, THE SYSTEM SHALL perform a TLS 1.2 handshake using a self-signed certificate, acting as the TLS server.

**FR-4:** WHEN TLS is established, THE SYSTEM SHALL exchange SERVICE_DISCOVERY messages to advertise available channels (video, audio, input, sensor).

**FR-5:** WHEN the head unit sends a CHANNEL_OPEN_REQUEST, THE SYSTEM SHALL open the requested channel and respond with CHANNEL_OPEN_RESPONSE.

### Video

**FR-6:** WHEN the video channel is opened, THE SYSTEM SHALL start a MediaProjection capture of the phone screen.

**FR-7:** THE SYSTEM SHALL encode captured frames using MediaCodec with H.264 Baseline Profile at the resolution negotiated with the head unit.

**FR-8:** THE SYSTEM SHALL support video resolutions of 480p, 720p, 1080p, and frame rates of 30fps and 60fps.

**FR-9:** WHEN encoded video frames are available, THE SYSTEM SHALL send them over the video channel as MEDIA_MESSAGE_DATA messages with appropriate framing.

### Audio

**FR-10:** WHEN the audio output channel is opened, THE SYSTEM SHALL capture phone audio using AudioPlaybackCapture API (Android 10+).

**FR-11:** THE SYSTEM SHALL encode audio as 48kHz stereo PCM or AAC-LC as negotiated.

**FR-12:** WHEN the microphone channel is opened, THE SYSTEM SHALL route received audio data to the phone's audio input for voice calls and assistant.

### Input

**FR-13:** WHEN touch events are received from the head unit, THE SYSTEM SHALL inject them into the Android input system via the Accessibility Service or InputManager.

**FR-14:** THE SYSTEM SHALL correctly map touch coordinates from head unit resolution to phone screen resolution.

**FR-15:** WHEN key events (back, home, media buttons) are received, THE SYSTEM SHALL dispatch them as Android KeyEvents.

### Sensors

**FR-16:** WHEN the head unit requests sensor data, THE SYSTEM SHALL provide GPS location from the phone's location provider.

**FR-17:** THE SYSTEM SHALL report night mode status based on the phone's UI mode or time of day.

### Protocol

**FR-18:** THE SYSTEM SHALL respond to PING_REQUEST messages with PING_RESPONSE to maintain the connection.

**FR-19:** WHEN a BYEBYE_REQUEST is received, THE SYSTEM SHALL release all resources and close the connection gracefully.

**FR-20:** THE SYSTEM SHALL handle message framing correctly, supporting single-frame and multi-frame (fragmented) messages.

---

## Non-Functional Requirements

**NFR-1:** THE SYSTEM SHALL work without root access on Android 12+ (LineageOS, stock Android).

**NFR-2:** THE SYSTEM SHALL NOT depend on Google Play Services or any proprietary Google libraries.

**NFR-3:** THE SYSTEM SHALL consume less than 15% CPU during active projection on a mid-range device.

**NFR-4:** THE SYSTEM SHALL use less than 200MB RAM during active projection.

**NFR-5:** THE SYSTEM SHALL be licensed under a permissive or copyleft open-source license (Apache-2.0 or GPL-3.0).

**NFR-6:** THE SYSTEM SHALL be written in Kotlin targeting Android SDK 32+.

---

## Out of Scope (v1)

- Car App Library hosting (apps rendering car-specific UI)
- Google Assistant integration
- SMS/messaging reply integration
- Phone call management
- Multiple simultaneous head unit connections
- Android Automotive OS support
