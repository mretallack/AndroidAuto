# Open Android Auto

An open-source implementation of the Android Auto **phone-side** app. This app runs on your phone and projects to a car's head unit over USB, replacing Google's proprietary `com.google.android.projection.gearhead` APK.

## ⚠️ WORK IN PROGRESS

This project is in early development. The protocol handshake and video projection are working with a real head unit. The phone screen is successfully displayed on the car's head unit for several seconds before disconnecting (video stability is being improved).

## Features

### Protocol & Connection
- [x] USB AOA accessory mode detection and connection
- [x] TLS 1.2 mutual authentication (phone as server)
- [x] Version negotiation (protocol v1.7)
- [x] Service discovery (request/response)
- [x] Channel open on target channels (video, audio, input, sensor)
- [x] Ping/pong keepalive (bidirectional)
- [x] Audio focus request/response handling
- [x] Navigation focus request/response handling
- [x] Voice session request handling
- [x] Graceful shutdown handling
- [x] Priority write queue (control messages over video)
- [ ] Wait for audio focus grant before sending audio (500ms timeout per HUIG)
- [ ] Bluetooth pairing exchange (BluetoothPairingRequest/Response)
- [ ] Handle multiple USB reconnections without AOAP re-init

### Video Projection
- [x] H.264 encoding via MediaCodec (800x480 @ 30fps, Baseline profile)
- [x] MediaProjection screen capture (with user permission dialog)
- [x] Video channel setup (SETUP → CONFIG → FOCUS → START flow)
- [x] Zero-based timestamps in microseconds
- [x] SPS/PPS prepended to keyframes (Annex B format)
- [x] Flow control (max_unacked tracking, backpressure)
- [x] Frame pacing (consistent 33ms intervals)
- [ ] Stable long-running video (currently disconnects after ~7 seconds)
- [ ] Resolution negotiation from head unit's service discovery
- [ ] Adaptive bitrate based on connection quality

### Touch Input
- [x] Input channel open and binding request
- [x] Touch event parsing (single and multi-touch)
- [x] Key event parsing (buttons, media keys)
- [x] Coordinate mapping (head unit → phone resolution)
- [x] TouchInjector with MotionEvent creation
- [ ] Inject touch events into VirtualDisplay
- [ ] Key event injection into Android system

### Audio
- [x] Audio channel open and setup
- [ ] Wait for audio focus grant before sending audio (500ms timeout per HUIG)
- [ ] Send AUDIO_FOCUS RELEASE on initial connect, then GAIN when playing
- [ ] Phone audio capture (MediaProjection AudioPlaybackCapture)
- [ ] PCM/AAC encoding and streaming to head unit
- [ ] Microphone input from head unit (voice commands)
- [ ] Multiple audio channels (media, system, speech, guidance)
- [ ] Audio silence streaming to keep channel active

### Sensors
- [x] Sensor channel open
- [x] Sensor start request/response handling
- [x] Night mode data parsing and sending
- [x] Driving status data parsing and sending
- [ ] GPS location forwarding
- [ ] Compass heading
- [ ] Car speed
- [ ] RPM
- [ ] Odometer (total + trip mileage)
- [ ] Fuel level and range
- [ ] Parking brake state
- [ ] Gear position (P/R/N/D/1-10)
- [ ] OBD-II diagnostics
- [ ] Environment (temperature, pressure, rain)
- [ ] HVAC (target/current temperature)
- [ ] Dead reckoning
- [ ] Passenger presence
- [ ] Door state (hood, boot, individual doors)
- [ ] Light state (headlights, indicators, hazards)
- [ ] Tire pressure
- [ ] Accelerometer (3-axis)
- [ ] Gyroscope (3-axis)
- [ ] GPS satellite data
- [ ] Respond to head unit sensor requests proactively

### Video (additional)
- [ ] Resolution negotiation from head unit's service discovery
- [ ] Adaptive bitrate based on connection quality
- [ ] Support for 720p, 1080p, 1440p, 4K resolutions
- [ ] Portrait mode resolutions (720x1280, 1080x1920, etc.)
- [ ] UI config updates (theme, insets)

### Other
- [ ] Bluetooth pairing coordination (A2DP, HFP)
- [ ] Navigation turn-by-turn to instrument cluster
- [ ] Navigation state (maneuvers, lanes, distances, current position)
- [ ] Media status (now playing info)
- [ ] Media playback metadata (track, artist, album)
- [ ] Media browser (browse phone media library from head unit)
- [ ] Phone status (call state notifications)
- [ ] Generic notifications (subscribe/unsubscribe system)
- [ ] Vendor extensions
- [ ] Wireless Android Auto (WiFi + Bluetooth handoff)
- [ ] Channel close notification
- [ ] Car connected devices request/response
- [ ] User switch request/response
- [ ] Battery status notification
- [ ] Call availability status
- [ ] Service discovery update (dynamic channel changes)
- [ ] Input feedback (haptic/visual feedback to head unit)
- [ ] Microphone request/response (voice input from head unit)
- [ ] Audio underflow notification
- [ ] Radio service (AM/FM/HD/DAB tuning, presets, RDS)

## ⚠️ DISCLAIMER

**USE AT YOUR OWN RISK.** This software is provided "as is", without warranty of any kind.

- This software may cause unexpected behaviour with your car's head unit
- This software may damage your phone or head unit — the authors accept no liability
- **DO NOT use this application while driving**
- **DO NOT interact with this application while operating a vehicle**
- This application is intended for development and testing purposes only
- Always pull over and stop your vehicle before interacting with any phone application
- The authors are not responsible for any accidents, injuries, or damages resulting from the use of this software

## Architecture

```
USB Plug-in → MainActivity → ProjectionService
                                    ↓
                    UsbAoaTransport (USB AOA accessory mode)
                                    ↓
                    MessageFramer (16KB frame fragmentation)
                                    ↓
                    InBandTls (TLSv1.2 via SSLEngine)
                                    ↓
                    ProtocolEngine (AAP state machine)
                                    ↓
                    ┌───────────┼───────────┐
                 Video      Input       Audio
                (H.264)   (touch/keys)  (PCM)
```

## Known Bugs

- **Channel assignment assumes order** — We assign the first `av_channel` in SERVICE_DISCOVERY_RESPONSE as video and the second as audio. This works with the car head unit (channel 1 = video) but fails with openauto (channel 4 = audio, not video). Fix: parse the `stream_type` field inside `av_channel` to distinguish `VIDEO(3)` from `AUDIO(1)`.
- **Video stability** — Connection drops after extended streaming due to head unit USB buffer overflow. See test results below.

### Video Stability Test Results

Test pattern (color bars) at 800x480, I-frame interval 1 second:

| FPS | Bitrate | Fragment | Duration | Frames | Status |
|-----|---------|----------|----------|--------|--------|
| 30 | 2Mbps | No | ~3s | ~90 | ❌ Too fast |
| 15 | 2Mbps | No | ~33s | ~500 | ⚠️ Better |
| 10 | 2Mbps | No | ~93s | ~930 | ⚠️ Good |
| 30 | 2Mbps | Yes (2KB) | 5-25s | 150-750 | ⚠️ Variable |
| 30 | 500Kbps | Yes (2KB) | ~54s | ~1691 | ⚠️ Better |
| 15 | 250Kbps | Yes (2KB) | ~67s+ | 1000+ | ✅ **Best so far** |
| 30 | 250Kbps | Yes (2KB), I=5s | ~20s | ~600 | ❌ Worse with long I-frame |

Root cause: Head unit USB receive buffer overflows with sustained high throughput. Lower data rate = longer connection.

## Building

```bash
./gradlew assembleDebug
```

Requires Android SDK with platform 35.

## Testing

### Unit Tests

```bash
./gradlew testDebugUnitTest
```

113 unit and integration tests covering protocol, framing, TLS, channel logic, video state machine, sensor handling, and touch input.

### Integration Testing with openauto (Docker)

openauto is a third-party head unit emulator that speaks the full Android Auto protocol. We use it to verify our protocol implementation without needing a real car.

#### Prerequisites

- Docker installed and running
- Phone connected via ADB (USB or wireless)
- App installed on phone: `./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk`

#### 1. Build the openauto Docker image (one-time)

```bash
cd thirdparty/openauto
docker build -f Dockerfile.headless -t openauto-headless .
```

This builds openauto with all dependencies (Qt5, boost, protobuf, OpenSSL) in a Debian container. Takes ~5 minutes on first build.

#### 2. Start openauto

```bash
docker run --rm -p 5100:5000 -e QT_QPA_PLATFORM=offscreen \
  openauto-headless timeout 60 /src/build/bin/autoapp
```

openauto listens on port 5000 inside the container, mapped to port 5100 on the host. It runs in headless mode (no display needed).

#### 3. Set up ADB reverse port forwarding

```bash
adb reverse tcp:5000 tcp:5100
```

This makes the phone's `localhost:5000` tunnel to the computer's `localhost:5100` (openauto). Our app connects to `localhost:5000` as a TCP client when no USB accessory is found.

#### 4. Start the app

```bash
adb shell am start -n org.openandroidauto/.MainActivity
```

The app will:
1. Fail to find a USB accessory
2. Connect to `localhost:5000` (openauto via adb reverse)
3. Perform the full protocol handshake (VERSION → TLS → AUTH → SERVICE_DISCOVERY)
4. Open channels (video, audio, input, sensor)
5. Start streaming video (test pattern)

#### 5. Verify in openauto logs

You should see in the openauto output:
```
[OpenAuto] handleNewClient() - Handle WIFI Client Connection
[OpenAuto] [AndroidAutoEntity] Send Version Request.
[OpenAuto] [AndroidAutoEntity] onVersionResponse()
[OpenAuto] [AndroidAutoEntity] Beginning SSL handshake.
[OpenAuto] [AndroidAutoEntity] Handshake completed.
[OpenAuto] [AndroidAutoEntity] onServiceDiscoveryRequest()
[OpenAuto] [AndroidAutoEntity] onAudioFocusRequest()
[OpenAuto] [AudioMediaSinkService] onChannelOpenRequest()
[OpenAuto] [VideoMediaSinkService] onChannelOpenRequest() (if video focus granted)
```

#### 6. Retrieve app logs

```bash
adb pull /sdcard/Android/data/org.openandroidauto/files/aa_log.txt
cat aa_log.txt
```

The log file persists on the phone between USB switches (useful when testing with a real car head unit).

#### Quick one-liner test

```bash
# Assumes openauto image already built and app installed
docker run --rm -p 5100:5000 -e QT_QPA_PLATFORM=offscreen openauto-headless timeout 20 /src/build/bin/autoapp &
sleep 3 && adb reverse tcp:5000 tcp:5100 && adb shell am start -n org.openandroidauto/.MainActivity
```

#### Notes

- openauto uses protocol v1.6; our app responds with v1.7 (both are accepted)
- openauto logs `Message Id not Handled: 4` for AUTH_COMPLETE — this is a known openauto quirk, not an error
- The connection should remain stable indefinitely (no timeout/disconnect)
- Video is not displayed (headless mode) but the protocol exchange is fully validated

## Protocol References

- [uglyoldbob/android-auto](https://github.com/uglyoldbob/android-auto) (Rust, LGPL-3.0) — protocol implementation with protobuf definitions
- [opencardev/aasdk](https://github.com/opencardev/aasdk) (C++, GPL-3.0) — updated protocol library with full protobuf definitions
- [opencardev/openauto](https://github.com/opencardev/openauto) (C++, GPL-3.0) — head-unit emulator (Crankshaft-NG)
- [tomasz-grobelny/AACS](https://github.com/tomasz-grobelny/AACS) (C++, GPL-3.0) — phone-side AA implementation for ODROID
- [headunit-revived](https://github.com/andreknieriem/headunit-revived) (Kotlin, AGPL-3.0) — head-unit side implementation
- [f1xpl/aasdk](https://github.com/f1xpl/aasdk) (C++, GPL-3.0) — original protocol library
- [GAL protocol research](https://milek7.pl/.stuff/galdocs/readme.md) — protocol notes, Wireshark dissector, and cached Head Unit Integration Guide

### Protobuf Definition Evolution

The Android Auto protocol was reverse-engineered across several projects. Each built on the previous:

| Project | Year | Proto Files | Role |
|---------|------|-------------|------|
| f1xpl/aasdk | 2018 | 1 monolithic (`Wifi.proto`) | Original RE — core protocol, video, audio, input, sensors |
| AACS | 2020 | 28 (split by message) | Phone-side impl — minimal coverage for video projection |
| opencardev/aasdk | 2024 | 254 (hierarchical by service) | Definitive reference — full protocol with all services |

**What opencardev/aasdk adds over AACS:**
- Radio service (AM/FM/HD/DAB tuning, presets, RDS, traffic)
- Navigation status (full turn-by-turn: maneuvers, lanes, distances, cues)
- Phone status (call state notifications)
- Media browser (browse phone media library from head unit)
- Media playback status (now playing metadata)
- Generic notifications (subscribe/unsubscribe system)
- WiFi projection (wireless AA credentials and access point config)
- GAL verification (Google Automotive Link testing/debugging)
- Instrument cluster (secondary display input)
- UI config (theme day/night, insets, display configuration)
- Battery status, user switch, toll card, EV connector types
- Service discovery update (dynamic channel changes)
- Extended video resolutions (1440p, portrait variants)
- Extended control messages (26 types vs 13)

**Key structural differences:**
- AACS uses `priority` + `channel_id` in ChannelOpenRequest; aasdk uses `priority` (sint32) + `service_id`
- AACS ServiceDiscoveryResponse is just a channel list; aasdk adds HeadUnitInfo, DriverPosition, PingConfiguration, ConnectionConfiguration
- aasdk separates media into sink (head unit receives) and source (head unit sends) with distinct message IDs

The `thirdparty/aasdk/protobuf/` directory is the authoritative protocol reference for this project.

## TLS Authentication

Android Auto uses mutual TLS. The phone acts as the TLS **server** and must present a certificate signed by the **Google Automotive Link CA** (baked into head unit firmware). Without the correct private key, the head unit rejects the connection with AUTH_COMPLETE status=-3.

### Certificate Chain

The phone presents a 2-cert chain:
1. **CarService cert** — `O=CarService`, signed by the Google Automotive Link CA
2. **Google Automotive Link CA** — self-signed root, `O=Google Automotive Link` (valid 2014-2044)

### How Authentication Works

1. The head unit has the **Google Automotive Link CA** public key baked into its firmware and trusts it
2. During TLS, the phone presents the **CarService cert** (which is signed by that CA)
3. The phone proves ownership of the cert by signing the TLS handshake with the corresponding **private key**
4. The head unit verifies the signature matches the cert's public key, and that the cert chains back to the trusted CA

### Certificate Rotation (Theory — Unconfirmed)

Google appears to rotate the cert+key embedded in the Android Auto APK approximately every 8 months (matching the cert's validity period). This may be a deliberate measure to limit the usefulness of extracted keys — if a head unit checks certificate expiry, an old extracted key would stop working. Users of the official app receive fresh certs via app updates. If this theory is correct, a user who never updates the official app could eventually be rejected by head units that enforce expiry. Not all head units may check expiry — this behaviour is model-dependent.

### Obtaining the Private Key

The private key is AES-256-CBC encrypted inside the Android Auto APK. The head unit validates the phone's cert against the Google Automotive Link CA — any cert signed by that CA is accepted.

#### Path A: Decrypt from the APK

The key is embedded (encrypted) in the Android Auto APK and can be decrypted using the APK's own algorithm. This requires any Android device with ADB access (no root, no Google Play Services needed) to run the decryption, because Android's Base64 decoder behaves differently from the desktop JVM.

**Requirements:**
- The Android Auto APK (pull from a phone with `adb pull`, or download from APKPure/APKMirror)
- Any Android device with ADB access to run the decryption (no root, no Google Play Services needed)
- Android SDK (`d8` build tool, `adb`)

**Process:**

1. Pull the AA APK from a phone: `adb pull $(adb shell pm path com.google.android.projection.gearhead | grep base | cut -d: -f2) aa.apk`
2. Decompile with JADX to find the cert provider class
3. Extract the binary data (256-byte KDF salt + ~1712-byte encrypted key + cert PEMs)
4. Compile the decryption Java class to DEX and run on-device with `dalvikvm`

**Finding the cert provider class (step 2):**

The class names are obfuscated and change between APK versions, but the structure is always the same. Search JADX for `"-----BEGIN CERTIFICATE-----"` — you'll find a small class implementing an interface with three methods:
- `a()` → returns a `String` (the CarService cert PEM)
- `b()` → returns a `byte[]` (~1712 bytes — the AES-encrypted private key)
- `c()` → returns a `byte[]` (256 bytes — the KDF salt)

Known class names by version:
| APK Version | Cert provider class | Salt+key class | Decryption class |
|---|---|---|---|
| v6.4 | `SslWrapper` (fields `o`, `p`) | same class | `SslWrapper.m23915f()` |
| v16.8 | `ivo` / `rqi` | `ivq` / `rql` (fields `b`, `c`) | `ivq.d()` |

The decryption function is in a nearby class — search for `"AES/CBC/PKCS5Padding"` to find it. It takes the cert provider interface as a parameter.

**Note:** The decryption step (step 4) only needs `dalvikvm` — any Android device with ADB works, no root or Google Play Services required. The GApps requirement is only for step 1 (pulling the APK, since the AA app is distributed via Play Store).

**Note:** You don't modify or run the decompiled APK code. Instead, you write a standalone `Decrypt.java` class that reimplements the decryption logic, reads the extracted byte arrays from files, and has its own `main()` entry point. The decompiled source is only used as a reference to understand the algorithm and copy out the byte arrays. See [`tools/decrypt_key_from_apk.md`](tools/decrypt_key_from_apk.md) for the complete `Decrypt.java` source.

**Critical JADX bug:** JADX decompiles the KDF helper as `byte b = bArr2[i2] & 255;` but it must be `int b = bArr2[i2] & 255;`. The `byte` type truncates back to signed, producing garbage output. Fix this to `int` and the decryption works.

The KDF (`tweakBytes`/`ap`) function:
```java
static void tweakBytes(byte[] bArr, byte[] bArr2, byte[] bArr3) {
    for (int i = 0; i < bArr.length; i++) {
        for (int i2 = 0; i2 < 48; i2++) {
            int b = bArr2[i2] & 255;  // MUST be int, not byte
            bArr2[i2] = (byte) (((((b >> 7) | (b + b)) + 33) ^ bArr3[i2 % bArr3.length]) ^ bArr[i]);
        }
    }
}
```

After AES decryption, the `T()` function extracts the key:
- Skip first 28 bytes, trim last 26 bytes
- Base64 decode (URL_SAFE, flag=2) the middle portion
- Result is PKCS#8 DER encoded RSA private key

**Note:** Must run on Android (not desktop JVM) due to `android.util.Base64` vs `java.util.Base64` differences. The desktop JVM's `Base64.getUrlDecoder()` rejects standard base64 characters (`+`, `/`) and newlines that Android's decoder accepts. Use `Base64.getMimeDecoder()` on desktop, or run the decryption on-device with `dalvikvm`:

```bash
# Compile to DEX and run on any Android device with ADB access
javac Decrypt.java -d out
d8 out/Decrypt.class --output dex_out
adb push dex_out/classes.dex /data/local/tmp/decrypt.dex
adb shell "dalvikvm -cp /data/local/tmp/decrypt.dex Decrypt"
```

This outputs the PKCS#8 private key in base64. Wrap it in PEM headers and place at `app/src/main/assets/carservice_key.pem`.

See [`tools/decrypt_key_from_apk.md`](tools/decrypt_key_from_apk.md) for the full step-by-step guide.

#### Path B: Use a previously extracted cert+key

Since some head units may not check certificate expiry, a previously extracted cert+key pair (even expired) may still work. Sources:

1. **Contact opengal_proxy author** — email `som@marekkraus.sk` (see [gamelaster/opengal_proxy](https://github.com/gamelaster/opengal_proxy))
2. **Extract from a rooted phone with GApps** — use Frida to hook `KeyFactory.generatePrivate()` (requires both root AND Google Play Services on the same device):
   ```bash
   frida -U -n "com.google.android.projection.gearhead" -l tools/dump_key_frida.js
   ```
3. **Community** — see [AACS#15](https://github.com/tomasz-grobelny/AACS/issues/15) for discussion

Once obtained, place the cert+key at `app/src/main/assets/carservice_key.pem`.

See `tools/dump_key.sh` and `tools/dump_key_frida.js` for runtime extraction scripts.

## License

This project is licensed under the [GNU General Public License v3.0](LICENSE).

This project includes protocol buffer definitions from [aasdk](https://github.com/f1xpl/aasdk) (GPLv3, Copyright © 2018 f1x.studio / Michal Szwaj) as a git submodule.
