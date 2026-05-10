# Android Auto Open Source Research

## Summary

The Android Auto protocol has been reverse-engineered by the community. Multiple working open-source implementations exist, ranging from Android apps to Raspberry Pi solutions. The most active and feature-complete project is **headunit-revived**.

---

## 1. History & Origins

### XDA Forums

- **Thread #3059481** — "Headunit app for Android Auto - Reverse Engineering AA USB Protocol"
  - Started by Mike Reid (mikereidis), who reverse-engineered the Android Auto USB protocol
  - Documented the USB handshake, TLS negotiation, and protobuf-based messaging
  - Led to the creation of the first open-source headunit app

- **Thread #3125252** — "HeadUnit for Android Auto"
  - Mike Reid's release thread for his headunit Android app
  - Demonstrated that any Android 4.1+ tablet with USB Host could act as an AA head unit
  - The original pioneer project; Mike Reid has since passed away

### Key Protocol Insights (from reverse engineering)

- Android Auto uses USB AOA (Android Open Accessory) for initial connection
- TLS 1.2 encryption wraps all communication after handshake
- Messages are protobuf-encoded
- Channels: Media (video/audio), Input (touch/buttons), Sensor, Navigation
- Video is H.264 (and now H.265) encoded, streamed from phone to head unit
- Audio is streamed from phone; microphone audio goes back to phone

---

## 2. Existing Projects

### headunit-revived (⭐ RECOMMENDED FOR REFERENCE)

| | |
|---|---|
| **URL** | https://github.com/andreknieriem/headunit-revived |
| **Stars** | 910 |
| **Commits** | 969 |
| **Language** | Kotlin/Java (Android app) |
| **License** | AGPL-3.0 |
| **Status** | Very active, on Google Play Store |

**What it does:**
- Turns any Android tablet/phone into an Android Auto receiver
- Supports USB wired and WiFi wireless connections
- Multitouch, H.265, up to 4K resolution, 60fps
- Portrait mode support
- Self-mode (phone projects to itself)
- Night mode (auto/manual/sensor)
- Key mapping for steering wheel controls
- WiFi Direct, NSD/mDNS discovery
- Companion "Wireless Helper" app for easy wireless setup

**How far they've got:**
- Fully functional, production-quality app available on Google Play
- 2.3.0-beta is latest, with extensive changelog showing continuous development
- Supports Android 4.1+ (API 16+)
- Has dealt with all the hard problems: SSL handshake, video decoding, audio routing, touch mapping

**Architecture notes:**
- Based on Mike Reid's original C/JNI approach but heavily modernized
- Uses native SSL libraries (JNI) with option for Java-based TLS
- Video decoding via Android MediaCodec
- Protobuf for protocol messages

---

### harryjph/android-auto-headunit (⭐ BEST FOR CLEAN KOTLIN REFERENCE)

| | |
|---|---|
| **URL** | https://github.com/harryjph/android-auto-headunit |
| **Stars** | 46 |
| **Commits** | 248 |
| **Language** | Kotlin (Android app) |
| **License** | AGPL-3.0 |
| **Status** | Less active but clean codebase |

**What it does:**
- Android Auto headunit emulator
- Based on Mike Reid's and Alex Gavrishev's work

**Key differentiator:**
- **Removed all C/JNI code** — pure Kotlin/Java implementation
- Re-implemented TLS layer in Kotlin using Java APIs
- Cleaner, more portable, smaller APK
- Multi-touch support
- Proper fullscreen/immersive mode
- Auto-trusts connected phones

**Why it matters for us:**
- Proves the entire AA protocol can be implemented in pure Kotlin without native code
- Much simpler build process (no NDK required)
- Easier to understand and modify
- Minimum API 18 (Android 4.3)

---

### openauto / opencardev

| | |
|---|---|
| **URL** | https://github.com/opencardev/openauto |
| **Stars** | 214 |
| **Commits** | 340 |
| **Language** | C++ |
| **License** | GPL-3.0 |
| **Status** | Maintained fork of f1xpl/openauto |

**What it does:**
- Android Auto headunit emulator for Raspberry Pi
- Uses `aasdk` library (https://github.com/f1xpl/aasdk) for protocol handling
- Part of the "Crankshaft" turnkey Raspberry Pi AA solution (2.1k stars)

**Architecture:**
- `aasdk` — standalone C++ library implementing the AA protocol
- `openauto` — Qt-based UI application using aasdk
- Handles USB, Bluetooth, WiFi connections
- Video rendering via Qt/OpenGL

---

### openDsh/dash

| | |
|---|---|
| **URL** | https://github.com/openDsh/dash |
| **Stars** | 454 |
| **Commits** | Many |
| **Language** | C++ |
| **License** | GPL-3.0 |
| **Status** | Active community |

**What it does:**
- Full dashboard solution combining openauto + aasdk
- Designed for Raspberry Pi car installations
- Includes media player, OBD-II, camera support alongside AA

---

### AACS (Android Auto Car Server)

| | |
|---|---|
| **URL** | https://github.com/tomasz-grobelny/AACS |
| **Stars** | 339 |
| **Commits** | 86 |
| **Language** | C++ |
| **License** | GPL-3.0 |
| **Status** | Proof of concept / research |

**What it does:**
- Accesses AA headunits as a video display (reverse direction — uses the car's screen)
- Runs on Odroid N2 with USB OTG
- Acts as proxy for AA traffic
- Streams video content to the car's headunit

**Components:**
- `AAServer` — communicates with car's headunit via USB OTG
- `AAClient` — communicates with mobile device
- `GetEvents` — forwards touch events via XTest

**Dependencies:** libusbgx, Snowmix (video mixing)

**Interesting because:** It approaches the problem from the opposite direction — pretending to be a phone to an existing car headunit.

---

### mikereidis/headunit (Original)

| | |
|---|---|
| **URL** | https://github.com/mikereidis/headunit |
| **Stars** | — |
| **Language** | Java/C (JNI) |
| **License** | AGPL-3.0 |
| **Status** | Archived (author deceased) |

The original that started it all. All Android-based projects derive from this work.

---

### iConsole/OpenHU

| | |
|---|---|
| **URL** | https://github.com/iConsole/OpenHU |
| **Stars** | 16 |
| **Commits** | 7 |
| **Language** | Java/JNI |
| **License** | AGPL-3.0 |
| **Status** | Minimal activity |

Continuation of Mike Reid's work. Mostly unchanged from original.

---

### Crankshaft

| | |
|---|---|
| **URL** | https://github.com/opencardev/crankshaft |
| **Stars** | 2,100 |
| **Language** | Shell/C++ |
| **Status** | Raspberry Pi distro |

Turnkey GNU/Linux solution that transforms a Raspberry Pi into an Android Auto head unit. Uses openauto + aasdk under the hood.

---

## 3. How Far Has the Community Got?

| Capability | Status |
|---|---|
| USB wired connection | ✅ Fully working |
| WiFi wireless connection | ✅ Working (multiple methods) |
| Video decoding (H.264) | ✅ Fully working |
| Video decoding (H.265) | ✅ Working |
| Up to 4K resolution | ✅ Working |
| 60fps | ✅ Working |
| Audio playback | ✅ Working |
| Microphone input | ✅ Working |
| Touch input | ✅ Working (multitouch) |
| Button/key mapping | ✅ Working |
| Night mode | ✅ Working |
| Portrait mode | ⚠️ Working with limitations |
| Self-mode (phone to itself) | ✅ Working |
| WiFi Direct | ✅ Working |
| Bluetooth auto-connect | ⚠️ Limited device support |
| Pure Kotlin (no JNI) | ✅ Proven possible |

---

## 4. Critical Distinction: Head Unit Side vs Phone Side

**All the projects above implement the HEAD UNIT side** — they receive the projection from a phone. They act as the display/receiver.

**Our goal is the PHONE side** — we want to build the app that runs on the phone and projects TO a car's head unit. This is a fundamentally different (and less explored) problem.

### What the phone side does:

- Acts as the TLS **server** (the head unit is the client)
- Captures and encodes the screen (H.264/H.265 video)
- Streams audio from media apps
- Receives touch/input events from the head unit
- Provides navigation, media, phone call services
- Manages app lifecycle and projection

### Who has worked on the phone side?

**Nobody has built a full open-source phone-side replacement.** The Google Android Auto APK (`com.google.android.projection.gearhead`) is the only phone-side implementation.

---

## 5. TLS/Authentication — How It Actually Works

From studying the existing head unit implementations and the `uglyoldbob/android-auto` Rust library:

### The protocol is NOT mTLS in the traditional sense

- The **head unit** authenticates using an X.509 **client certificate** (self-signed is fine)
- The **phone** (Android Auto app) acts as the TLS **server**
- The head unit connects TO the phone and presents its certificate
- The phone accepts any client certificate — it's not checking against a CA
- The certificate is used for **identity/pairing**, not traditional PKI trust

### Key insight from existing projects:

- `headunit-revived` and others ship with a **hardcoded self-signed cert/key pair** (originally extracted from Mike Reid's work)
- The phone doesn't validate the cert against a trusted CA — it just uses it for the TLS channel
- First connection triggers a "trust this device?" prompt on the phone
- After trust is established, the cert fingerprint is remembered

### What this means for us (phone side):

- We need to implement a TLS **server** that accepts client certificates
- We generate our own server cert or extract the one from the AA APK
- The protocol itself is not cryptographically locked down — it's security through obscurity + the TLS channel
- Extracting the protobuf definitions from the APK is straightforward (they're in the binary)

---

## 6. uglyoldbob/android-auto — Rust Protocol Library (⭐ KEY REFERENCE)

| | |
|---|---|
| **URL** | https://github.com/uglyoldbob/android-auto |
| **Stars** | 10 |
| **Commits** | 95 |
| **Language** | Rust |
| **License** | LGPL-3.0 |
| **Status** | Active (2025), on crates.io |
| **crates.io** | `android-auto` v0.3.3 |

**This is the most modern and well-documented protocol implementation.** While it's still head-unit side, it's a clean-room Rust implementation with:

- Full protocol documentation in code
- Protobuf definitions for ALL channels (in `protobuf/` dir)
- TLS handling via rustls
- USB and Wireless (Bluetooth + WiFi) support
- Async architecture (tokio)
- Published as a reusable library crate

### Architecture (from their README):

```
Transport Layer → TLS Handshake → Frame Layer → Channel Layer
     (USB/WiFi)    (rustls)      (fragmentation)  (protobuf messages)
```

### Channels implemented:
- Video, Media Audio, Speech Audio, System Audio
- Input (touch + keycodes)
- Sensors (night mode, driving status)
- Navigation (turn-by-turn)
- Bluetooth
- Media Status
- Control

### Why this matters for us:

1. **Protobuf definitions** — we can use their `.proto` files directly
2. **Protocol state machine** — documented in Rust, easy to understand
3. **TLS details** — shows exactly how the handshake works (V1 certificates, client cert auth)
4. **LGPL-3.0** — we can use the library or reference it without viral licensing concerns

---

## 7. Feasibility of Building the Phone Side

### Approach 1: Reverse engineer the AA APK

- Download `com.google.android.projection.gearhead` APK
- Extract protobuf definitions (already done by others — see uglyoldbob's `protobuf/` dir)
- Extract/understand the TLS server certificate handling
- Understand the video encoding pipeline
- Understand the service advertisement (USB AOA descriptor + Bluetooth SDP)

### Approach 2: Build from protocol knowledge

Using the head-unit implementations as reference (they document what the phone sends/receives), we can infer the phone-side behavior:

- **Video**: Phone captures screen → encodes H.264 → sends over video channel
- **Audio**: Phone captures media audio → encodes → sends over audio channel
- **Input**: Phone receives touch events from head unit → injects into Android input system
- **Navigation**: Phone sends turn-by-turn data from nav apps
- **Control**: Phone handles service discovery, channel setup, capability negotiation

### Key challenges for phone side:

1. **Screen capture** — requires MediaProjection API or root access
2. **Audio capture** — requires AudioPlaybackCapture API (Android 10+) or root
3. **Input injection** — requires accessibility service or root
4. **USB AOA server mode** — phone needs to present as an AOA accessory (this is what the official AA app does)
5. **Integration with Android system** — media session, notifications, phone calls

### What we can extract from the APK:

- Protobuf `.proto` definitions (decompile with jadx/apktool)
- TLS certificate and key (if embedded)
- USB AOA descriptors
- Service capability declarations

---

## 8. Recommendation: Best Repos to Study

### For protocol understanding:

1. **uglyoldbob/android-auto** (Rust) — Most modern, cleanest protocol implementation. Has protobuf definitions. LGPL-3.0.
2. **f1xpl/aasdk** (C++) — Original protocol library, well-tested.
3. **headunit-revived** (Kotlin/Java) — Shows real-world edge cases and workarounds.

### For our phone-side Kotlin app:

- Use protobuf definitions from `uglyoldbob/android-auto` or extract from APK
- Study the head-unit implementations to understand what the phone is expected to send/receive
- The TLS is not a blocker — self-signed certs work, no CA validation
- Main challenge is Android system integration (screen capture, audio capture, input injection)

### Next steps:

1. Extract and document the protobuf message definitions
2. Understand the USB AOA handshake from the phone's perspective
3. Prototype TLS server that accepts head unit connections
4. Implement screen capture → H.264 encoding → video channel
5. Implement audio routing

---

## 9. APK Analysis: Android Auto 16.8.661804-release

### XAPK Structure

```
com.google.android.projection.gearhead (34MB base APK)
├── classes.dex (9.3MB)
├── classes2.dex (7.7MB)
├── classes3.dex (7.5MB)
├── classes4.dex (558KB)
├── assets/ (AltFormats, Metadata files)
├── res/ (resources)
└── AndroidManifest.xml (140KB)

Split APKs:
├── config.arm64_v8a.apk (5.4MB) — native libs
├── config.xxhdpi.apk (2.6MB) — high-DPI resources
└── config.{lang}.apk — language packs
```

### Native Libraries (arm64-v8a)

- `libgmm-jni.so` — Google Maps/Media
- `libhwrword.so` — Handwriting recognition
- `libmappedcountercacheversionjni.so` — Performance counters
- `libresampling_jni.so` — Audio resampling
- `libandroidx.graphics.path.so` — Graphics path rendering

**Notable: NO native SSL/TLS library.** All TLS is done in Java via `SSLContext.getInstance("TLSv1.2")`.

### Embedded Certificate (Google Automotive Link CA)

Found in obfuscated class `ivq.java`:

```
Issuer: C=US, ST=California, L=Mountain View, O=Google Automotive Link
Validity: Jun 6 2014 — Jun 5 2044 (30-year self-signed CA)
Key: RSA 2048-bit
Signature: sha1WithRSAEncryption
```

This is the **trust anchor** for the TLS connection. The phone uses this CA cert to validate head unit client certificates.

### TLS Setup Details

```java
// From ivq.java (deobfuscated logic):
SSLContext.getInstance("TLSv1.2", "GmsCore_OpenSSL")  // preferred
SSLContext.getInstance("TLSv1.2")                      // fallback

// Private key derivation:
// 1. Device-specific data (from shared prefs) used as seed
// 2. Cert PEM bytes mixed in
// 3. 7 rounds of key derivation via custom hash function
// 4. AES/CBC/PKCS5Padding decrypts the stored private key
// 5. KeyManager + TrustManager initialized with result
```

The private key is **device-specific** — derived from data stored in the app's preferences, mixed with the CA cert. This means each phone generates its own key pair on first setup.

### USB AOA Descriptors

```java
// From rtt.java:
manufacturer = "Android"
model = "Android Auto" OR "Android Open Automotive Protocol"
```

The phone looks for USB accessories with these model strings to identify a connected head unit.

### Protocol Messages (from skx.java)

#### Control Channel Messages:
| Message | Description |
|---------|-------------|
| VERSION_REQUEST/RESPONSE | Protocol version negotiation |
| ENCAPSULATED_SSL | TLS handshake data |
| AUTH_COMPLETE | Authentication finished |
| SERVICE_DISCOVERY_REQUEST/RESPONSE/UPDATE | Capability exchange |
| CHANNEL_OPEN_REQUEST/RESPONSE | Open logical channel |
| CHANNEL_CLOSE_NOTIFICATION | Close channel |
| PING_REQUEST/RESPONSE | Keep-alive |
| NAV_FOCUS_REQUEST/NOTIFICATION | Navigation focus |
| BYEBYE_REQUEST/RESPONSE | Graceful disconnect |
| VOICE_SESSION_NOTIFICATION | Voice assistant |
| AUDIO_FOCUS_REQUEST/NOTIFICATION | Audio routing |
| BATTERY_STATUS_NOTIFICATION | Phone battery |
| CAR_CONNECTED_DEVICES_REQUEST/RESPONSE | Multi-device |
| USER_SWITCH_REQUEST/RESPONSE | User profiles |
| CALL_AVAILABILITY_STATUS | Phone call state |

#### Media Channel Messages:
| Message | Description |
|---------|-------------|
| DATA | Raw media data (video/audio frames) |
| CODEC_CONFIG | Codec configuration data |
| SETUP | Initialize media channel |
| START/STOP | Control playback |
| CONFIG | Configuration update |
| ACK | Acknowledgment |
| MICROPHONE_REQUEST/RESPONSE | Mic access |
| VIDEO_FOCUS_REQUEST/NOTIFICATION | Video focus |
| UPDATE_UI_CONFIG_REQUEST/REPLY | UI configuration |
| AUDIO_UNDERFLOW_NOTIFICATION | Buffer underrun |
| MEDIA_STATS | Performance metrics |
| MEDIA_OPTIONS | Codec/quality options |
| CRITICAL_UI_NOTIFICATION | Safety-critical UI |
| INTEGRATED_OVERLAY_* | Overlay management |

### Video Codecs (from xib.java)

| ID | Codec |
|----|-------|
| 3 | H.264 Baseline Profile |
| 5 | VP9 |
| 6 | AV1 |
| 7 | H.265 (HEVC) |

### Audio Codecs (from xib.java)

| ID | Codec |
|----|-------|
| 1 | PCM (raw) |
| 2 | AAC-LC |
| 4 | AAC-LC ADTS |

### Audio Formats (from ijq.java)

| ID | Format |
|----|--------|
| 1 | 48000 Hz Stereo |
| 2 | 16000 Hz Mono |
| 3 | 48000 Hz Mono |

### Sensor Types (from xlp.java)

| ID | Sensor |
|----|--------|
| 1 | Location (GPS) |
| 2 | Compass |
| 3 | Speed |
| 4 | RPM |
| 5 | Odometer |
| 6 | Fuel level |
| 7 | Parking brake |
| 8 | Gear position |
| 9 | OBD-II diagnostic codes |
| 10 | Night mode |
| 11 | Environment data |
| 12 | HVAC data |
| 13 | Driving status |
| 14 | Dead reckoning |
| 15 | Passenger data |
| 16 | Door data |
| 17 | Light data |
| 18 | Tire pressure |
| 19 | Accelerometer |
| 20 | Gyroscope |
| 21 | GPS satellite data |
| 22 | Toll card |
| 23 | Vehicle energy model |
| 24 | Trailer data |
| 25 | Raw vehicle energy model |
| 26 | Raw EV trip settings |

### Projection Mechanism

The app uses `DisplayManager.createVirtualDisplay()` — NOT `MediaProjection`. This requires the privileged permission:

```
android.permission.TOGGLE_AUTOMOTIVE_PROJECTION
android.permission.CREATE_VIRTUAL_DEVICE
android.permission.ADD_TRUSTED_DISPLAY
```

These are **signature-level permissions** — only available to system apps or apps signed with the platform key. This is the key barrier for a non-root replacement.

### Key Permissions Required

**Privileged (system app only):**
- `TOGGLE_AUTOMOTIVE_PROJECTION` — enables projection
- `CREATE_VIRTUAL_DEVICE` — virtual display creation
- `BLUETOOTH_PRIVILEGED` — BT operations
- `MODIFY_AUDIO_ROUTING` — audio routing
- `MANAGE_USB` — USB control
- `START_ACTIVITIES_FROM_BACKGROUND` — launch without user interaction

**Normal/Dangerous (user-grantable):**
- `RECORD_AUDIO` — microphone
- `ACCESS_FINE_LOCATION` — GPS
- `READ_CONTACTS`, `READ_CALL_LOG` — phone integration
- `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN` — BT
- `NEARBY_WIFI_DEVICES` — WiFi Direct

### Obfuscation Level

Code is heavily obfuscated with ProGuard/R8:
- All classes in `defpackage` with random 2-4 letter names
- No meaningful package structure preserved
- String constants are mostly intact (enum values, log tags)
- The `com.google.android.gms.car.senderprotocol` package survived (Channel, ChannelMessage classes)

### Key Insight: senderprotocol Package

The `com.google.android.gms.car.senderprotocol` package contains:
- `Channel.java` — represents a logical channel with open/close/send operations
- `ChannelMessage.java` — Parcelable message with channel ID, ByteBuffer payload, flags
- Messages are sent as ByteBuffers with a 2-byte message type prefix followed by protobuf payload

---

## 10. Implications for Our Implementation

### What we now know:

1. **TLS is standard** — TLSv1.2 with a self-signed CA cert. We can generate our own cert/key pair.
2. **No native crypto** — everything is Java SSL, so Kotlin implementation is straightforward.
3. **Protocol is protobuf** — messages have a 2-byte type header + protobuf body.
4. **The real blocker is permissions** — `TOGGLE_AUTOMOTIVE_PROJECTION` is signature-level.

### Options for LineageOS (non-root):

1. **Build as a system app** — possible on LineageOS if you build from source
2. **Use Magisk/root** — grant privileged permissions
3. **Use MediaProjection API instead** — works without root but shows persistent notification and requires user approval each time
4. **Shizuku** — grants ADB-level permissions without full root

### Recommended approach:

Since you're on LineageOS, the most practical path is:
1. Start with MediaProjection API for screen capture (works without root)
2. Use standard Bluetooth/WiFi APIs for connectivity
3. Later, add support for privileged mode when running as system app on custom ROMs
