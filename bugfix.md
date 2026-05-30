# Video Disconnect Bug

## Problem
Head unit disconnects (USB EIO) after video streaming starts. Duration varies from 3-93 seconds depending on configuration. Video IS displayed correctly on the head unit during streaming. Connection is stable indefinitely when video is NOT streaming.

## Root Cause
**Likely found.** The phone was sending AUTH_COMPLETE (message 0x04) back to the head unit after receiving the HU's AUTH_COMPLETE. The HU does not expect this message and responded with MESSAGE_UNEXPECTED_MESSAGE (0x00FF). This put the HU into an internal error state that caused it to disconnect after a timeout (~8.5 min without video, ~51s with video). Removing the AUTH_COMPLETE response fixed the idle disconnect — connection now stable for 30+ minutes without video. Video streaming tested for 200 frames (~13s) without error but needs longer test to confirm fully resolved.

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

1. **The 0x00FF message / SERVICE_DISCOVERY_REQUEST format error** — The HU sends "unexpected message" (0x00FF) immediately after our SERVICE_DISCOVERY_REQUEST. This may indicate our request format is wrong (bad field, extra data, or wrong timing). The HU may tolerate this initially but eventually disconnect. The official app likely sends a correctly-formatted request that doesn't trigger 0x00FF.

2. **TLS record size** — Large video keyframes (~5KB) encrypted as single TLS records may overflow the HU's TLS receive buffer. This would explain why video accelerates the disconnect (51s vs 511s). The official app likely fragments into smaller TLS records.

3. **Missing protocol message during streaming** — There may be a periodic message the real AA app sends that we don't (e.g., media status update, video channel heartbeat, or periodic VIDEO_FOCUS renewal).

4. **ACK handling / flow control drift** — Our unacked counter or sequence numbering may drift over time, eventually confusing the head unit.

5. **Write pattern / USB transfer timing** — The official app may batch or pace USB writes differently.

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
