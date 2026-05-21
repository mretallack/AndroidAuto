# Video Disconnect Bug

## Problem
Head unit disconnects (USB EIO) after video streaming starts. Duration varies from 3-93 seconds depending on configuration. Video IS displayed correctly on the head unit during streaming. Connection is stable indefinitely when video is NOT streaming.

## Root Cause
**Unknown.** Initially appeared to be bandwidth-related (lower fps = longer duration), but further testing shows even very low data rates (5 KB/s) still disconnect. The disconnect is specifically triggered by sending video data, regardless of rate.

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

## What We've Ruled Out
- ❌ Bandwidth overflow (5 KB/s still disconnects)
- ❌ Flow control / max_unacked (never reached)
- ❌ Ping timeout (pings answered correctly, priority queue)
- ❌ Missing sensor data (NACKed — head unit doesn't want it from us)
- ❌ Missing Bluetooth pairing (NACKed — not expected)
- ❌ Audio channel timeout (audio silence doesn't help during video)
- ❌ Protocol version (v1.7 matches real app)
- ❌ Codec config format (both with and without separate message fail)
- ❌ Annex B vs AVCC (both work, head unit decodes fine)

## Remaining Hypotheses

1. **USB bulk transfer size** — Individual large writes (5-15KB keyframes as single TLS records) may cause the head unit's USB controller to reset. The 10fps test worked longest because keyframes are spaced further apart (100ms vs 33ms at 30fps), giving the USB controller recovery time between large transfers.

2. **TLS record size** — The head unit may have a maximum TLS record size it can buffer. A 15KB keyframe encrypted as one TLS record requires the head unit to buffer the entire record before decrypting. Smaller records (from lower bitrate) work longer.

3. **Head unit firmware bug** — The head unit may have a known issue with sustained video streaming. The real Google AA app might use a specific workaround we don't know about (e.g., periodic video STOP/START, or specific timing between frames).

4. **Missing protocol message** — There may be a periodic message the real AA app sends during video streaming that we don't (e.g., a heartbeat on the video channel, or periodic VIDEO_FOCUS renewal).

5. **USB electrical/physical issue** — The USB cable or port may have marginal signal integrity that degrades under sustained high-frequency writes.

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
