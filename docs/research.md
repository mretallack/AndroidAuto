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
