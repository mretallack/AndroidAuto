# Video Disconnect Bug

## Problem
Head unit disconnects (USB EIO) after video streaming starts. Duration depends on data rate — lower fps = longer connection. The video IS displayed correctly on the head unit during streaming.

## Root Cause (confirmed)
**Bandwidth/throughput issue.** The head unit cannot process video data fast enough at high rates. The USB bulk transfer or internal buffer fills up and the head unit resets the USB connection.

Evidence:
| FPS | Duration | Data Rate (approx) |
|-----|----------|-------------------|
| 30  | ~3 seconds | ~500 KB/s |
| 15  | ~33 seconds | ~250 KB/s |
| 10  | **93 seconds** | ~170 KB/s |
| 0 (no video) | **indefinite** | ~1 KB/s (pings only) |

## What Works
- Full protocol: VERSION(v1.7) → TLS → AUTH → SERVICE_DISCOVERY → CHANNEL_OPEN → SETUP → CONFIG → FOCUS → START
- H.264 test pattern encoding via MediaCodec (800x480, Baseline profile)
- Head unit decodes and displays video correctly (color bars visible)
- Head unit sends ACKs (0x8004) for every video frame
- Pings answered bidirectionally
- Audio silence streaming keeps connection alive indefinitely without video
- Connection stable for 93 seconds at 10fps

## Attempted Fixes and Results

| # | Fix | Result |
|---|-----|--------|
| 1 | Send CODEC_CONFIG as type 0x0001 | **Worse** — 1.5s |
| 2 | Frame rate throttling (drop > 30fps) | Same ~7s |
| 3 | Flow control (pause at max_unacked=100) | Same ~7s |
| 4 | Priority write queue (pings over video) | Same ~7s |
| 5 | Bidirectional pings (1/sec) | 5s → 7s |
| 6 | Open audio channel + SETUP | No change |
| 7 | Input BINDING_REQUEST | No change |
| 8 | Zero-based timestamps | No change |
| 9 | SPS/PPS prepended to keyframes | No change |
| 10 | Annex B format check/conversion | No change |
| 11 | Frame pacing (sleep between frames) | No change |
| 12 | Remove frame dropping | No change |
| 13 | Reduce MAX_FRAME_PAYLOAD to 2000 (fragment ciphertext) | **Broke TLS** — head unit can't parse split TLS records |
| 14 | Send codec config as MediaIndication (0x0001) | **Worse** — 1.3s |
| 15 | Test pattern (no MediaProjection) | 7s → 10s |
| 16 | AUDIO_FOCUS_REQUEST (GAIN) | No change |
| 17 | Protocol version 1.5 → 1.7 | Correct, no change alone |
| 18 | Send sensor data on channel 6 | **NACKed (0xFF)** — wrong direction |
| 19 | BluetoothPairingRequest on channel 5 | **NACKed (0xFF)** — not expected |
| 20 | Audio silence on channel 3 | Keeps alive without video, no help during video |
| 21 | Reduce fps to 15 | **33 seconds** ✓ |
| 22 | Reduce fps to 10 | **93 seconds** ✓ |
| 23 | Fragment-before-encrypt (2KB chunks, FIRST/MIDDLE/LAST) | **Broke protocol** — head unit didn't ACK, no video displayed, 1.6s disconnect |

## Fragment-Before-Encrypt Analysis

**What we tried (#23):** Split plaintext into 2KB chunks, encrypt each chunk separately into its own TLS record, send each as FIRST/MIDDLE/LAST frames.

**Result:** Head unit received frames but never sent ACKs. Video was not displayed. Disconnected after 1.6 seconds. The head unit could not reassemble our fragmented messages.

**What AACS does differently:** Looking at AACS `getMessage()` function:
- It encrypts the plaintext chunk with `SSL_write()` then reads the TLS record with `BIO_read()`
- The FIRST frame includes a 4-byte **total plaintext length** before the 2-byte chunk length
- It uses `maxSize = 2000` for the plaintext chunk before encryption
- The framing is: `[channel:1][flags:1][total_len:4][chunk_len:2][encrypted_chunk:N]` for FIRST
- And: `[channel:1][flags:1][chunk_len:2][encrypted_chunk:N]` for MIDDLE/LAST

**Why ours failed:** Our FIRST frame format may be wrong. AACS puts the total plaintext length in the FIRST frame header, but the head unit might expect the total to be the sum of all encrypted chunks (which is larger due to TLS overhead). Or the head unit's TLS implementation expects to receive complete TLS records and can't handle partial records split across frames.

**The fundamental issue:** The head unit's TLS implementation likely buffers the entire TLS record before decrypting. If we send one large TLS record (15KB), the head unit must buffer all 15KB before it can decrypt and process the video frame. This fills its buffer and causes the disconnect.

## Options to Reduce Data Rate at 30fps

1. **Reduce bitrate** — Currently 2Mbps. Drop to 500Kbps-1Mbps. Frames will be smaller (1-3KB instead of 5-15KB). Simplest change, no protocol impact.

2. **Reduce resolution** — Drop from 800x480 to 400x240. Quarter the pixels = quarter the data. Head unit would upscale.

3. **Increase I-frame interval** — Currently 1 second (every 30 frames is a keyframe). Increase to 5 seconds. Keyframes are 5-15KB, P-frames are 200-1000 bytes. Fewer keyframes = less data spikes.

4. **Fix fragment-before-encrypt** — Need to understand exactly how the head unit reassembles fragments. May need to study the AACS code more carefully or capture real AA app traffic.

5. **Limit max frame size** — Configure MediaCodec to produce smaller NAL units (max-slice-size). Each NAL would be under 2KB, fitting in a single TLS record without fragmentation.

## How Other Systems Handle This

**AACS (phone-side, C++):**
- Uses `maxSize = 2000` for plaintext chunks
- Encrypts each chunk separately with `SSL_write()` / `BIO_read()`
- Sends FIRST frame with 4-byte total plaintext length + 2-byte chunk length
- MIDDLE/LAST frames have just 2-byte chunk length
- The head unit reassembles plaintext from decrypted chunks

**Real Google AA app:**
- Unknown internal implementation
- Likely uses the same fragment-before-encrypt as AACS (same protocol)
- Works at 30fps/60fps without issues on the same head unit

**Key difference:** The real AA app and AACS both fragment BEFORE encryption. We encrypt first (producing one large TLS record) then the MessageFramer can only split the ciphertext (which breaks TLS). The correct approach is to split the plaintext, but our implementation of the framing was wrong.

## Next Steps (prioritized)
1. **Try reducing bitrate to 500Kbps at 30fps** — quickest test, one line change
2. **Try increasing I-frame interval to 5 seconds** — reduces keyframe spikes
3. **Re-examine AACS fragment format** — the total_length field in FIRST frame needs to match what the head unit expects
4. **Try max-slice-size in MediaCodec** — force encoder to produce small NALs
