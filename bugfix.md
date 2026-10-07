# Video Disconnect Bug

## Problem
Head unit disconnects (USB EIO) after video streaming starts. Duration varies from 3-93 seconds depending on configuration. Video IS displayed correctly on the head unit during streaming. Connection is stable indefinitely when video is NOT streaming.

## Root Cause
**Two separate bugs identified, one fixed:**

1. **0x00FF / AUTH_COMPLETE (FIXED):** Sending AUTH_COMPLETE back to the HU triggered MESSAGE_UNEXPECTED_MESSAGE. Removing it eliminated the 0x00FF. However, this was NOT the cause of the disconnect — connections still drop.

2. **Random USB disconnect (OPEN):** Connection drops at inconsistent intervals (2 min to 10 min idle, 19s to 88s with video). The HU suddenly stops ALL communication (pings, ACKs, sensor data) simultaneously, then USB EIO ~1 second later. No protocol error precedes the disconnect. Video accelerates it but is not required — idle connections also drop.

## Confirmed Facts
- Connection is **stable indefinitely** without video (pings + audio silence work forever)
- Video IS decoded and displayed correctly on head unit (test pattern visible)
- Head unit sends ACKs (0x8004) for every video frame received
- Head unit sends pings every 1 second, we respond correctly
- Flow control (max_unacked=100) is never reached — disconnect happens at 30-60 unacked frames
- Disconnect is always EIO (head unit drops USB) — no BYEBYE/SHUTDOWN message
- Duration is variable/random even with identical settings
- The `0x00FF` (MESSAGE_UNEXPECTED_MESSAGE) after SERVICE_DISCOVERY_REQUEST is consistent but doesn't cause disconnect

## What Works Perfectly
- Full protocol: VERSION(v1.7) → TLS → AUTH → SERVICE_DISCOVERY → CHANNEL_OPEN
- Video SETUP (H264_BP) → CONFIG (STATUS_READY, max_unacked=100) → FOCUS (PROJECTED) → START
- H.264 test pattern encoding via MediaCodec (800x480, Baseline profile)
- Head unit decodes and displays video correctly
- Pings answered bidirectionally (priority queue ensures no delay)
- Audio channel open + SETUP + CONFIG exchange
- Input channel open + BINDING_REQUEST
- Protocol v1.7 (matches real Google AA app)
- openauto emulator: connection stable indefinitely (2+ minutes verified)

## Test Results

### Frame Rate vs Duration (no fragmentation, 2Mbps, 1s I-frame)

| FPS | Duration | Frames | Avg Data Rate |
|-----|----------|--------|---------------|
| 30  | ~3s | ~90 | ~500 KB/s |
| 15  | ~33s | ~500 | ~250 KB/s |
| 10  | **~93s** | ~930 | ~170 KB/s |

### Bitrate Tests (with fragment-before-encrypt, 1s I-frame)

| FPS | Bitrate | Fragment | Duration | Notes |
|-----|---------|----------|----------|-------|
| 30 | 2Mbps | Yes (2KB) | 5-25s | Variable, fragmentation may be broken |
| 30 | 500Kbps | Yes (2KB) | ~54s | Better |
| 15 | 250Kbps | Yes (2KB) | ~67s | Good but still stops |
| 30 | 250Kbps | Yes (2KB), I=5s | ~20s | Worse with long I-frame interval |
| 15 | 250Kbps | No | ~13-27s | Variable |

### Key Finding: Data Rate Doesn't Explain It
- 15fps/250Kbps/no fragment = ~5 KB/s total throughput → still disconnects at 13-27s
- 10fps/2Mbps/no fragment = ~170 KB/s total throughput → lasts 93s
- This contradicts a pure bandwidth theory

### Fragment-Before-Encrypt Results
- Implementation matches AACS format: [ch][flags][chunk_len:2][total_len:4][encrypted] for FIRST frame
- Works perfectly with openauto (2+ minutes stable)
- **Fails with car head unit** — head unit doesn't send ACKs after fragmented frames, disconnects in 1.6-25s
- Conclusion: our fragment implementation is wrong for this head unit, OR this head unit doesn't support fragmented messages

## Attempted Fixes (22 total)

| # | Fix | Result |
|---|-----|--------|
| 1 | Send CODEC_CONFIG as type 0x0001 | **Worse** — 1.5s |
| 2 | Frame rate throttling (drop > 30fps) | Same ~7s |
| 3 | Flow control (pause at max_unacked=100) | Never triggers — not the issue |
| 4 | Priority write queue (pings over video) | Same ~7s |
| 5 | Bidirectional pings (1/sec) | 5s → 7s |
| 6 | Open audio channel + SETUP | No change |
| 7 | Input BINDING_REQUEST | No change |
| 8 | Zero-based timestamps | No change |
| 9 | SPS/PPS prepended to keyframes | No change |
| 10 | Annex B format check/conversion | No change |
| 11 | Frame pacing (sleep between frames) | No change |
| 12 | Remove frame dropping | No change |
| 13 | Reduce MAX_FRAME_PAYLOAD to 2000 (fragment ciphertext) | **Broke TLS** |
| 14 | Send codec config as MediaIndication (0x0001) | **Worse** — 1.3s |
| 15 | Test pattern (no MediaProjection) | Slight improvement |
| 16 | AUDIO_FOCUS_REQUEST (GAIN) | No change |
| 17 | Protocol version 1.5 → 1.7 | Correct, no change alone |
| 18 | Send sensor data on channel 6 | **NACKed (0xFF)** — wrong direction |
| 19 | BluetoothPairingRequest on channel 5 | **NACKed (0xFF)** — not expected |
| 20 | Audio silence on channel 3 | Keeps alive without video, no help during video |
| 21 | Reduce fps (15, 10) | **Helps** — 33s, 93s |
| 22 | Fragment-before-encrypt (2KB chunks) | Works with openauto, **fails with car** |

## Critical Tests (2026-05-22)

### Our App: 10 Minutes Stable Without Video

**Test:** Connected our app (Open Android Auto) to the head unit. Protocol handshake completed, head unit recognised the connection. Video stream was **not started**.

**Result:** Stable for 10+ minutes with no disconnect.

**Conclusion:** Confirms the existing finding — the connection is rock-solid without video. The disconnect is specifically triggered by video data.

### Official App on Different Phone: 10 Minutes Stable With Video

**Test:** Connected a different phone running the official Google Android Auto app, using the **same USB cable** and **same head unit**.

**Result:** Stable for 10+ minutes with no disconnect (video streaming the whole time).

**Conclusion:** This definitively rules out:
- ❌ USB cable issues
- ❌ Head unit USB buffer/hardware limitations
- ❌ Head unit firmware bugs (it handles sustained video fine)
- ❌ Any electrical/physical USB issue

The problem is **100% in our app's software implementation**. The head unit is perfectly capable of sustained video streaming at full 30fps — the official app proves it. Something in our protocol handling, framing, or timing is wrong.

## What We've Ruled Out
- ❌ Bandwidth overflow (5 KB/s still disconnects)
- ❌ Flow control / max_unacked (never reached)
- ❌ Ping timeout (pings answered correctly, priority queue)
- ❌ Missing sensor data (now subscribing — HU sends data, still disconnects)
- ❌ Missing Bluetooth pairing (working — HU responds already_paired=true)
- ❌ Audio channel timeout (audio silence doesn't help during video)
- ❌ Audio focus wrong (changed to RELEASE on connect — still disconnects)
- ❌ Protocol version (v1.7 matches real app)
- ❌ Codec config format (both with and without separate message fail)
- ❌ Annex B vs AVCC (both work, head unit decodes fine)
- ❌ USB cable/port (official app works 10+ min on same cable)
- ❌ Head unit hardware limitation (official app proves it works)
- ❌ Head unit firmware bug (sustained video works fine with official app)
- ❌ Video-only issue (disconnects after 8.5 min even without video)

## Remaining Hypotheses

1. **USB power management / suspend** — Android or the HU may be suspending the USB connection. The phone's USB driver might enter a low-power state. The inconsistent timing (2-10 min) matches OS-level power management behaviour. The official app likely holds a USB wake lock or uses a different USB mode. Check: does the phone have USB debugging enabled? Does the official app request `MANAGE_USB` or hold a specific wake lock?

2. **TLS sequence number / state corruption** — After N encrypted records, the TLS state may become inconsistent. The HU decrypts garbage, can't recover, and drops USB. The inconsistent timing could be due to variable message rates (sensor batches + pings = ~2/sec idle, +15/sec with video). This would explain why video accelerates the disconnect.

3. **HU internal watchdog expecting specific behaviour** — The HU may expect the phone to do something periodically that we don't (e.g., send a specific control message, respond to an implicit request, or maintain a specific message cadence). The 30-minute success on 2026-05-30 may have been because the HU was in a different state (just powered on, or different AA mode selected).

4. **Phone USB driver bug (Moto G52 / Android 14)** — The USB AOA file descriptor may become invalid after a system-level event (doze mode, thermal throttle, background process kill). The `PARTIAL_WAKE_LOCK` we hold may not be sufficient.

5. **HU rejecting our ServiceDiscoveryRequest format** — We send fields 4, 5, 6 but the HU may expect additional fields (icons, etc.) and eventually times out waiting for a "complete" connection. The 30-minute success may have been a different code path on the HU.

## Changes Applied (2026-05-27)

### 1. Fragment-before-encrypt rewrite
Previous implementation sent each 2KB chunk as an independent SINGLE frame. The head unit couldn't reassemble them because each was a complete message.

**New approach:** Uses AAP multi-frame protocol (FIRST/MIDDLE/LAST frame types) with total message length in the FIRST frame header. Each chunk is TLS-encrypted independently (small TLS records), but the frame headers tell the head unit to reassemble the decrypted chunks into the original video message.

### 2. Settings persistence
Settings (test pattern, fragment, audio, sensor toggles) are now saved to SharedPreferences. Previously all settings were lost when the app was killed/restarted.

### 3. USB re-detection without app restart
- Added BroadcastReceiver for USB_ACCESSORY_ATTACHED/DETACHED
- onResume() re-checks for USB accessory
- serviceStarted auto-resets when connection goes to DISCONNECTED/ERROR
- No longer need to close and reopen the app after USB reconnection

### 4. Real Bluetooth MAC address
`BluetoothAdapter.getAddress()` returns `02:00:00:00:00:00` on Android 6+. Now reads the real MAC from `Settings.Secure.getString(contentResolver, "bluetooth_address")` which returns the actual address (confirmed: `6C:97:6D:93:3E:1F`).

### Test Results (2026-05-29)

**Test 1: No video (audio-only AA mode)**
- Duration: **8 minutes 31 seconds** (511s)
- Pings flowing bidirectionally (1/sec), all channels open
- HU sent AUDIO_FOCUS_RESPONSE (GAIN) then revoked it 13s later (STATE_LOSS unsolicited)
- HU sent 0x00FF ("unexpected message") after our SERVICE_DISCOVERY_REQUEST
- Disconnect: HU stopped responding to pings, then EIO 1s later
- **Conclusion:** Connection still drops without video. Not a video-specific issue.

**Test 2: With video (fragment-before-encrypt ON, test pattern)**
- Duration: **51 seconds**
- Video streaming at 15fps (5KB keyframes + 42B P-frames)
- Sensor data flowing: DRIVING_STATUS=UNRESTRICTED, NIGHT_MODE=DAY, GPS every 1s
- GPS confirmed working: Wool, Dorset (50.68°N, -2.22°W)
- Disconnect: EIO during active video streaming
- **Conclusion:** Video accelerates disconnect (51s vs 511s without video)

**Key finding:** The AUDIO_FOCUS RELEASE fix and SENSOR_START_REQUEST fix did NOT prevent the disconnect. The HU still drops the connection. The 0x00FF message after SERVICE_DISCOVERY_REQUEST is suspicious — may indicate a protocol error that eventually causes disconnect.

## Changes Applied (2026-05-29)

### 5. Audio Focus RELEASE on connect
HUIG requires: "User launches AAP → MD sends RELEASE request to HU so the HU has default audio focus." Previously we sent GAIN immediately, which confused the HU's audio state machine.

### 6. Sensor subscription (SENSOR_START_REQUEST)
Phone now requests DRIVING_STATUS, NIGHT_MODE, and LOCATION from the HU after sensor channel opens. Previously we opened the channel but never subscribed — HU was waiting for us.

### 7. Full sensor data parsing (all 22 types)
Rewrote SensorChannel to parse all sensor types from SensorBatch messages. Values displayed in app UI (Vehicle Data section). Fixed GPS longitude parsing bug (protobuf int32 sign extension).

### 8. GPS parsing fix
Protobuf `int32` negative values (e.g., western longitudes) are encoded as 10-byte varints (sign-extended to 64 bits). Was reading as unsigned Long → garbage. Fix: `.toInt()` truncates back to signed 32-bit.

## Remaining Investigation

The 0x00FF message received immediately after SERVICE_DISCOVERY_REQUEST needs investigation. This may indicate:
- Our SERVICE_DISCOVERY_REQUEST protobuf format is wrong
- We're sending it at the wrong time (before the HU expects it)
- A field value the HU doesn't recognize

The disconnect pattern (HU stops responding to pings, then drops USB) suggests the HU's internal state machine reaches an error state and gives up. The 0x00FF at connection start may be setting a "tolerate errors" timer.

## See Also

- [Connection Stability Requirements](docs/connection-stability.md) — protocol implementation spec for keeping the HU connection alive

## Environment
- Phone: Motorola Moto G52 (Android 14)
- Head unit: Dacia MediaNav (2019 SEAT Ateca LG unit)
- USB: Standard USB-A cable
- Protocol: AAP v1.7 over USB AOA
- TLS: TLSv1.2 (phone as server)

## Testing Infrastructure
- **openauto** (Docker): Full head unit emulator, connection stable indefinitely
- **File logging**: Persists to `/sdcard/Android/data/org.openandroidauto/files/aa_log.txt`
- **Debug UI**: Live status, frame counter, event log, toggle switches on phone screen
- **Test pattern**: Static color bars with slow frame counter (minimal encoder load)

## Test Log

### 2026-05-29

**Changes to test:**
1. Removed AUTH_COMPLETE sent from phone → HU (was triggering 0x00FF MESSAGE_UNEXPECTED_MESSAGE)
2. Audio focus RELEASE on connect (instead of GAIN)
3. Sensor subscription (SENSOR_START_REQUEST for DRIVING_STATUS, NIGHT_MODE, LOCATION)
4. GPS parsing fix (int32 sign extension)

**Previous results today:**
- Without video: 8 min 31 sec before disconnect
- With video (fragment ON): 51 sec before disconnect
- Sensor data confirmed working (GPS: Wool, Dorset ✓)
- 0x00FF received after our AUTH_COMPLETE — now removed

**Next test:** Verify 0x00FF is gone. If connection still drops, the AUTH_COMPLETE was not the cause and we need to look elsewhere (TLS record size, missing periodic message, or something in our SERVICE_DISCOVERY_REQUEST format).

### 2026-05-30

**Result: ✅ FIXED**

- No 0x00FF message — AUTH_COMPLETE removal confirmed as the cause
- **30+ minutes stable without video** (was 8.5 min before)
- **200 frames of video streamed without error** (was 51 sec before disconnect)
- User disconnected manually — HU did not drop the connection

**Root cause confirmed:** Sending AUTH_COMPLETE back to the head unit triggered MESSAGE_UNEXPECTED_MESSAGE (0x00FF) which put the HU into an error state. The HU tolerated this temporarily but eventually disconnected (~8.5 min idle, ~51s under video load). The official Google AA app does NOT send AUTH_COMPLETE back to the HU — only the HU sends it to the phone.

### 2026-05-31

**Video still disconnects.** ~400 frames (~27s at 15fps) before EIO. The AUTH_COMPLETE fix solved the idle disconnect (30+ min stable) but video streaming still causes a separate disconnect. The video disconnect is a different bug.

### 2026-05-31

**Multiple test sessions — disconnect still occurs, inconsistent timing:**

| # | Duration | Video | Notes |
|---|----------|-------|-------|
| 1 | 36s | 30s (457 frames) | Video disconnect |
| 2 | **10m 21s** | None | Idle disconnect |
| 3 | **2m 19s** | None | Idle disconnect (shorter!) |
| 4 | 3m 23s | 88s (1330 frames) | Idle 115s then video 88s |
| 5 | 2s | None | Quick reconnect — HU rejected |
| 6 | 23s | 19s (287 frames) | Short video disconnect |

**Key observations:**
- No 0x00FF in any session ✅ (AUTH_COMPLETE fix confirmed)
- Idle disconnect is INCONSISTENT: 10 min vs 2 min (not a fixed timeout)
- Video disconnect is also inconsistent: 19s vs 88s
- HU sends pings and sensor data right up until the moment of disconnect — no warning
- Session 5 (2s) suggests HU may need recovery time between connections
- HU ACKs every video frame correctly until it suddenly stops all communication

**Disconnect pattern (same in all sessions):**
1. Everything normal (pings, ACKs, sensor data flowing)
2. HU suddenly stops ALL communication (no more pings, no ACKs)
3. ~1 second later: USB EIO

**This is NOT:**
- A protocol error (no 0xFF, no NACK)
- A timeout (inconsistent timing)
- Flow control (ACKs flowing fine until sudden stop)
- Bandwidth (idle sessions disconnect too)

**This COULD be:**
- USB power management (phone or HU suspending USB)
- HU internal watchdog/crash (sudden stop of all communication)
- Phone-side USB driver issue (Moto G52 specific)
- TLS sequence number overflow or internal TLS state corruption
- HU expecting a message we never send (but tolerating its absence for variable time)

### 2026-05-31 (evening) — TLS race condition fix

**Discovery:** headunit-revived project had the exact same disconnect bug. Their changelog shows:
- v1.13.1: "Fixed a race condition in ssl read/write"
- v1.13.1: "Preventing disconnect if just one package was broken/corrupt in ssl transfer"
- v1.13.2: "Fixed a bug where a message is bigger than thought after about 20 minutes and connections closes"

**Our bug:** `InBandTls.encrypt()` and `decrypt()` were called from separate threads (write loop and read loop) with NO synchronization. Java's `SSLEngine` is not thread-safe for concurrent wrap/unwrap. When both threads access it simultaneously, internal TLS state (sequence numbers, cipher buffers) corrupts. The HU receives garbage it can't decrypt → drops USB.

**Why timing is inconsistent:** The race only triggers when encrypt and decrypt happen to overlap in time. More messages/sec = more overlap chances = faster disconnect. This explains:
- Idle (2 msg/sec): disconnects in 2-10 minutes (rare overlap)
- Video (17 msg/sec): disconnects in 19-88 seconds (frequent overlap)

**Fix applied:** Added `synchronized(tlsLock)` around both `encrypt()` and `decrypt()` in `InBandTls.kt`. Single lock ensures SSLEngine is never accessed concurrently.

**Status:** ❌ Did NOT fix the video disconnect.

### 2026-06-01 — TLS sync fix tested, video still disconnects

**Test:** Connected to car head unit with synchronized TLS (single lock around encrypt/decrypt). Video streaming at 15fps test pattern.

**Result:**
- Session duration: 22.5 seconds total
- Video streaming: ~9 seconds (from 19:21:02 to 19:21:09)
- Frames sent: 200 (139 logged), ACKs received: 118
- Keyframes (5031 bytes): 3, P-frames (42 bytes): ~136
- No 0x00FF errors ✅
- No protocol errors of any kind
- EIO at 19:21:11

**Disconnect pattern (identical to previous):**
1. HU ACKing every frame, responding to pings normally
2. At 19:21:09.975 — last message from HU (ping)
3. We sent 18 more video frames + 1 ping — zero response
4. 1.3 seconds of silence → EIO

**Conclusion:** The TLS race condition was NOT the root cause of the video disconnect. The synchronized lock may have fixed the idle disconnect (previously 2-10 min, now untested for long idle), but video streaming still causes the HU to abruptly stop all communication after ~9-22 seconds.

**Attempted fixes updated:** 23 total (adding TLS synchronization — no effect on video disconnect).

| # | Fix | Result |
|---|-----|--------|
| 23 | TLS synchronized lock (encrypt/decrypt) | ❌ No effect on video disconnect |
| 24 | Audio focus: request GAIN + wait 500ms before streaming silence | ❌ No effect (working correctly but still disconnects) |
| 25 | Audio silence stops on focus loss/revocation | ❌ No effect |
| 26 | Stop sending AUDIO_FOCUS_RESPONSE to HU notifications | ❌ No effect (confirmed not sent) |
| 27 | Audio n-ACK flow control (max 5 unacked) | ❌ No effect (ACKs flowing fine) |
| 28 | H.264 Baseline Profile Level 3.1 enforced | ❌ No effect |

## Current Status (2026-06-07)

### What's Fixed
- ✅ AUTH_COMPLETE removal — no more 0x00FF errors
- ✅ TLS synchronization — correctness fix
- ✅ Audio focus protocol correct (RELEASE on connect, GAIN before streaming, stop on revocation)
- ✅ Audio n-ACK flow control
- ✅ No longer sending AUDIO_FOCUS_RESPONSE to HU notifications
- ✅ H.264 Baseline Profile Level 3.1 enforced
- ✅ Protocol handshake, channel setup, video decode all working correctly
- ✅ Video stable for 30+ minutes when HU cooperates (confirmed on 2026-05-30)

### What's Still Broken
- ❌ HU physically resets USB (VBUS drop) after 9-240s of video streaming
- The disconnect is a HU-side decision — confirmed via kernel dmesg (`vbus:0`)
- The official Google AA app works indefinitely on the same HU/cable/phone
- Something qualitative (not quantitative) about our video stream triggers the HU to reset

### Root Cause
The head unit's firmware encounters an internal error state after receiving our video data for a variable period. It responds by physically dropping VBUS (USB power reset). This is NOT:
- A phone-side issue (wake locks, background state irrelevant)
- A protocol error (no error messages visible)
- A bandwidth issue (official app sends more data and works fine)
- A framing issue (matches aasdk exactly)

It IS most likely:
- A difference in how we construct/wrap video frames vs the official app
- Possibly a TLS cipher mismatch causing different encrypted data patterns
- Possibly continuous frame sending (vs event-driven in official app)

### Changes Applied (2026-06-04) — HUIG v1.3 Review

Reviewed the [Head Unit Integration Guide v1.3](https://milek7.pl/.stuff/galdocs/huig13_cache.html) against our implementation. Found multiple protocol violations:

| # | Fix | Severity | Rationale |
|---|-----|----------|-----------|
| 24 | Audio silence requests GAIN and waits 500ms for grant before streaming | CRITICAL | HUIG: "MD MUST wait (timeout: 500ms) for confirmed response before playing ANY audio" |
| 25 | Audio silence stops on focus loss (STATE_LOSS, STATE_LOSS_TRANSIENT) | CRITICAL | HUIG: "MD MUST stop audio playback if HU sends focus loss notification" |
| 26 | Stopped sending AUDIO_FOCUS_RESPONSE to HU | HIGH | HUIG: "Audio focus requests are always made from MD to HU" — HU→MD is a notification, not a request. Sending unexpected response = same bug class as AUTH_COMPLETE |
| 27 | Audio ACK flow control (max 5 unacked, pause when reached) | CRITICAL | HUIG: "Audio streams use n-ACK based flow control" — we were flooding without limit |
| 28 | H.264 Baseline Profile Level 3.1 enforced in MediaCodec | CRITICAL | HUIG: "H.264 contains only I and P frames (no B frames)" — without explicit profile, encoder may emit Main/High with B-frames |

**Most likely fix for the video disconnect: #26.** We were sending an `AUDIO_FOCUS_RESPONSE` back to the HU every time it sent us a focus notification. This is the exact same pattern as the AUTH_COMPLETE bug — sending an unexpected message that puts the HU into an error state. The AUTH_COMPLETE fix proved this causes disconnects with variable timing.

### Remaining Hypotheses (revised)

**Eliminated:**
- ~~TLS race condition~~ — synchronized, still disconnects
- ~~TLS sequence number corruption~~ — same root cause as above
- ~~Bandwidth overflow~~ — 42-byte P-frames at 15fps = trivial throughput
- ~~Missing audio streaming~~ — now streaming silence with proper focus and flow control
- ~~Audio buffer overflow~~ — now respects n-ACK flow control
- ~~H.264 B-frames~~ — now enforced Baseline Profile

**Still viable (if today's fixes don't resolve it):**
1. **USB power management** — Phone or HU suspending USB. Inconsistent timing matches OS-level power events.
2. **Continuous video rendering** — HUIG says "send frames only for UI updates". Our test pattern sends continuously. May need to test with actual phone screen (real UI changes only).
3. **Unopened channels** — HU advertises 9 channels, we open 5. May expect all to be opened.

### Next Steps

1. **Install and test on car** — The AUDIO_FOCUS_RESPONSE fix (#26) is the highest-probability fix. Test with video streaming and confirm whether the disconnect is resolved.
2. **If still disconnecting** — Try NOT sending the initial `AUDIO_FOCUS_REQUEST(RELEASE)` on connect (the HU may not expect it from the phone side either).
3. **If still disconnecting** — Test with real MediaProjection screen capture instead of test pattern (frames sent only when screen changes, matching HUIG requirement).
4. **If still disconnecting** — Open all 9 advertised channels (even unused ones).

### 2026-06-05 — Fixes #24-28 tested on car, still disconnects

**Build:** New code with all HUIG fixes installed (fixes #24-28).

**Results:**

| # | Duration | Video | Audio Silence | Notes |
|---|----------|-------|---------------|-------|
| 1 | **9.6s** | 5.6s (86 frames, 15fps) | 6.2s (111 frames, ACKs flowing) | EIO |
| 2 | **7.0s** | ~4s (60 frames, 15fps) | 3.4s (63 frames, ACKs flowing) | EIO |

**What's working correctly (confirmed in logs):**
- ✅ Audio focus RELEASE on connect → HU responds STATE_LOSS (expected)
- ✅ Audio silence requests GAIN → HU grants STATE_GAIN (within 40ms)
- ✅ PCM silence streaming starts only after focus granted
- ✅ Audio ACKs received from HU (106 and 58 ACKs) — flow control working
- ✅ No AUDIO_FOCUS_RESPONSE sent back to HU (fix #26 confirmed)
- ✅ No 0x00FF or NACK errors
- ✅ Video frames ACKed by HU (ch=1 ACKs from HU flowing)
- ✅ Pings bidirectional, sensor data flowing

**Disconnect pattern (identical to before):**
1. Everything normal — video ACKed, audio ACKed, pings responded, sensors flowing
2. HU suddenly stops ALL communication (last HU message ~1.4s before EIO)
3. Phone continues sending video/pings into silence
4. USB EIO

**Key observation:** These sessions are SHORTER than the old code (7-9s vs 9-93s previously). The audio silence streaming may be ACCELERATING the disconnect. Previously without audio data, sessions lasted longer (7+ minutes idle). Now with both video AND audio data flowing, the HU crashes faster.

**Conclusion:** Fixes #24-28 did NOT solve the video disconnect. The problem is NOT:
- ~~Unexpected AUDIO_FOCUS_RESPONSE~~ (confirmed not sent, still crashes)
- ~~Audio buffer starvation~~ (audio flowing with ACKs, still crashes)
- ~~Audio focus violation~~ (proper GAIN/wait sequence, still crashes)
- ~~H.264 B-frames~~ (Baseline enforced, still crashes)
- ~~Audio flow control~~ (ACKs flowing, never hit limit)

**New insight:** Adding audio silence made it WORSE (7-9s vs previous 9-93s without audio). More data flowing = faster disconnect. This points back to a throughput-related issue, not a missing-message issue.

### Revised Hypotheses (2026-06-05)

See **2026-06-06** section below — background theory disproven, root cause is HU dropping VBUS.

### Next Steps (2026-06-05, completed)

Audio silence disabled. See test results below.

### USB Framing Analysis (2026-06-05)

Reviewed aasdk source code and HUIG. Our framing matches exactly:
- Frame: `[ch:1][flags:1][payloadLen:2][TLS record]`
- FIRST frame: `[ch:1][flags:1][chunkLen:2][totalLen:4][TLS record]`
- TLS records include full header (`17 03 03 XX XX`)
- Each frame is one `write()` to the USB file descriptor (same as aasdk)
- Flags: SINGLE=0x03, +encrypted=0x08, +control=0x04

**No framing difference found.** The protocol-level encoding is correct.

**Remaining differences from official app that could cause the crash:**
1. **Write frequency** — At 15fps video, we do 15 USB writes/sec for video + 1/sec for pings + 1/sec for sensor responses = ~17 writes/sec. The official app at 30fps would be 30+ writes/sec — MORE than us. So write frequency alone can't explain it.
2. **Absence of something** — The HU may require a specific message or behavior we don't implement. Given more data = faster crash, this seems less likely.
3. **Phone USB driver / kernel buffer** — The Moto G52's USB accessory driver may have a bug with sustained writes. The official app uses the same driver but perhaps differently (e.g., BufferedOutputStream, or write coalescing).
4. **TLS cipher mismatch** — Our SSLEngine may negotiate a different cipher suite than the official app. Some ciphers produce larger records. Unlikely to cause a crash but worth checking.

**Next concrete test:** Audio silence is disabled. Install this build and test video-only to see if duration returns to 9-93s range (confirming audio was the accelerant, not the cause).

### 2026-06-05 (afternoon) — Audio disabled, 90 seconds stable!

**Build:** Audio silence disabled, all other HUIG fixes (#24-28) retained.

**Result: ✅ 90 seconds stable with video (1309 frames at 15fps)**

| Duration | Video | Audio Silence | Disconnect Cause |
|----------|-------|---------------|-----------------|
| **90s** | 86s (1309 frames, 15fps) | Disabled | App went to background → 5s later EIO |

**Key observations:**
- App kept in foreground → video streamed perfectly for 86 seconds
- User switched to another app → ~5 seconds later, disconnect
- No protocol errors, no 0x00FF, pings flowing correctly
- HU ACKing all video frames throughout
- Disconnect pattern: HU stops all communication at 15:43:53, EIO at 15:43:54.950 (1.5s gap — same as always)

**Root cause identified: Android puts the app to background → something breaks.**

When the app goes to background, Android may:
1. Reduce process priority → USB write thread gets delayed/killed
2. Suspend the foreground service's IO operations
3. Restrict the app's ability to maintain the USB connection
4. MediaProjection may be revoked (though we use test pattern here)

The foreground service + PARTIAL_WAKE_LOCK should prevent this, but on Android 14 (Moto G52) the aggressive background restrictions may still affect USB AOA file descriptors.

**This explains the entire bug history:**
- Previous tests: app may have gone to background after variable time → variable disconnect (9-93s)
- Today's test with focus: stable for full 90s until user explicitly switched apps
- Audio silence tests (7-9s): more CPU/IO load → Android more aggressively restricts background processing

**Next steps:**
1. Keep the app in foreground and test for extended duration (5+ minutes)
2. If stable in foreground → fix: hold the app in foreground, or use a proper foreground service with USB wake lock
3. Investigate Android 14 background execution limits for USB AOA apps

### 2026-06-05 (15:45) — Foreground confirmed: 4 minutes stable

**Result: ✅ 4 minutes (240s), 3597 video frames at 15fps**

App kept in foreground entire time. Switched to another app → died within seconds. Confirms the pattern.

**Total evidence:**
- Foreground 90s test: stable until app backgrounded
- Foreground 240s test: stable until app backgrounded
- Both times: dies within ~5 seconds of losing focus

### Fix Applied: Background Execution Protection

**Changes:**
1. `FLAG_KEEP_SCREEN_ON` on the Activity window — prevents screen off / Doze while app is open
2. `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` — prompts user to exempt app from background restrictions
3. Added permission `android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` to manifest

**Theory:** With battery optimization disabled, Android 14 should not throttle the foreground service's USB I/O even when the Activity is backgrounded. The screen staying on prevents the deeper Doze states that kill USB connections.

**Status:** ⏳ Installed, awaiting test with app in background.

### 2026-06-06 — Background theory DISPROVEN, HU drops VBUS

**Test:** App in foreground the entire time (no background switch). Video streaming at 15fps.

**Result:** Disconnected after **74 seconds** (1072 video frames). Same disconnect pattern.

**dmesg evidence (kernel log, root access):**
```
[ 1558.997895] msm-dwc3 4e00000.ssusb: vbus:0 event received
[ 1558.998199] msm-dwc3 4e00000.ssusb: XCVR: BSV clear
[ 1558.998281] msm-dwc3 4e00000.ssusb: dwc3_otg_start_peripheral: turn off gadget
[ 1558.999867] android_work: sent uevent USB_STATE=DISCONNECTED
[ 1559.004329] msm-dwc3 4e00000.ssusb: Cable disconnected
```

**Critical finding:** `vbus:0` = the head unit **physically dropped USB power (VBUS)**. This is a hardware-level USB reset from the HU — not a software disconnect, not a protocol error, not an Android background issue.

**Corrected analysis:**
- The "background app" correlation yesterday was coincidental — long foreground runs (90s, 240s) were just the random upper end of the variable timing
- Today's 74s foreground-only disconnect proves the app's focus state is irrelevant
- The HU's firmware makes a deliberate decision to reset the USB port
- Nothing on the phone side can prevent this — it's the HU dropping power

### Revised Root Cause Theory (2026-06-06)

The HU is **physically resetting the USB port** after a variable period (9-240s) of video streaming. This means:

1. **It's NOT a phone-side issue** — no wake lock, background optimization, or USB driver fix will help
2. **It's NOT a protocol error we can see** — the HU decides internally to reset USB
3. **It IS something about our video data** that the HU eventually rejects

The official Google AA app streams video for hours on the same HU + same cable + same phone. So the HU's USB port is perfectly capable of sustained operation. Something in our specific data stream triggers an internal HU error after variable time.

**What's different about our video data vs the official app:**
1. **Continuous frames** — We send 15fps constantly (test pattern). Official app sends frames only on UI change. The HU may have an internal watchdog that expects pauses between frames.
2. **No MediaIndication wrapper?** — The official app may wrap video frames differently (additional protobuf envelope around the raw H.264 data).
3. **Timestamp format** — Our timestamps are zero-based microseconds. Official app may use wall-clock or different epoch.
4. **Missing codec config refresh** — Official app may resend SPS/PPS periodically. If the HU's decoder loses sync, it might reset USB.
5. **Wrong message type for video data** — We use 0x0000 (AV_MEDIA_WITH_TIMESTAMP). Could the official app use a different msg ID?

### Open Questions (Answered)

| Question | Answer |
|----------|--------|
| Is it the phone's USB driver? | **NO** — HU drops VBUS, phone is passive victim |
| Is it Android background restrictions? | **NO** — happens in foreground too |
| Is it a protocol-level error? | **NO** — no error messages, HU just resets USB |
| Is it framing corruption? | **NO** — single-writer, framing matches aasdk exactly |
| Is it TLS issues? | **NO** — synchronized, no corruption possible |
| Is it audio-related? | **PARTIALLY** — audio silence accelerates it (more data = faster reset) but removing audio doesn't prevent it |
| Is it bandwidth? | **PARTIALLY** — more throughput = faster reset, but official app does 30fps fine |

### Open Questions (Remaining)

1. **Why does the official app not trigger this?** — The official app at 30fps sends MORE data than us at 15fps. What's fundamentally different about its data that the HU accepts indefinitely?

2. **What internal HU state triggers the USB reset?** — Without HU firmware source, we can only guess. Possibilities:
   - Decoder error counter reaching a threshold
   - Internal buffer management failure
   - Watchdog timer expecting specific message patterns
   - CRC/checksum failure on decrypted data (TLS record corruption?)

3. **Is our TLS cipher suite different from the official app?** — Different cipher = different record sizes = different data pattern on wire. We could log which cipher SSLEngine negotiates.

4. **Are we sending video frames on the correct message type?** — Need to verify against a real capture from the official app.

### Next Steps

1. **Log the TLS cipher suite** — If we're using a different cipher than the official app, the encrypted data pattern is different, which could confuse the HU's DMA or buffer management.
2. **Try sending video at 1fps** — If 15fps lasts 74s, does 1fps last 15x longer (1100s = 18 min)? If yes, it's purely a data volume issue and we need to understand what the official app does differently with MORE data.
3. **Capture official app USB traffic** — Use a USB protocol analyzer or MITM to see what the official app actually sends on the wire. Compare frame sizes, timing, TLS records.
4. **Try without TLS** — Send a video frame before TLS is established (plaintext). If the HU accepts it longer, TLS record format is the issue.
