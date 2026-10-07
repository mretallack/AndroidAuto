# Connection Stability Requirements

Lessons learned from debugging HU disconnects. The head unit (HU) will tear down the USB session if the phone-side implementation violates any of these requirements.

## 1. Control Channel Pings (Implemented ✅)

The HU and phone exchange `PingRequest`/`PingResponse` on Channel 0 (Control).

- **Frequency:** 1 second (our HU; spec says up to 5s)
- **Requirement:** Phone must respond to HU pings immediately
- **Implementation:** Priority write queue ensures ping responses are never blocked behind video frames
- **Failure mode:** If pings are delayed >3-5s, HU drops connection

## 2. Audio Sink Must Be Fed (Fixed ✅)

When an audio channel is opened and SETUP/CONFIG exchanged, the HU allocates a ring buffer for incoming PCM data.

- **Requirement:** If the audio channel is open, the phone must either:
  - Stream continuous PCM data (even silence), OR
  - Explicitly close the channel with AudioStop
- **HUIG addition:** MD MUST request AUDIO_FOCUS(GAIN) and wait up to 500ms for the grant BEFORE sending any audio frames. MD MUST stop sending audio immediately when the HU sends STATE_LOSS or STATE_LOSS_TRANSIENT.
- **Implementation:** Requests GAIN, waits for grant, then streams 48kHz stereo 16-bit PCM zeros in 50ms chunks. Stops immediately on focus revocation (both via AUDIO_FOCUS_RESPONSE and unsolicited AUDIO_FOCUS_REQUEST from HU).
- **Status:** Fixed 2026-06-04, pending car test.

## 3. AUTH_COMPLETE Must Not Be Sent to HU (Fixed ✅)

The AUTH_COMPLETE message is sent **only** from HU → phone. Sending it back triggers `MESSAGE_UNEXPECTED_MESSAGE` (0x00FF) which puts the HU into an error state. It tolerates this temporarily but eventually disconnects.

- **Symptom:** Idle disconnect at ~8.5 min, video disconnect at ~51s
- **Fix:** Remove AUTH_COMPLETE from phone → HU path
- **Confirmed:** 2026-05-30, 30+ min stable after fix

## 4. Write Serialization (Implemented ✅)

All channels are multiplexed over a single USB bulk endpoint pair. Every packet must be perfectly framed.

- **Requirement:** The entire encapsulation (AA header + protobuf + raw bytes) must be written atomically. Interleaved writes from multiple threads cause the HU's parser to lose alignment — it reads garbage where it expects a header and drops USB immediately.
- **Implementation:** Single-writer coroutine (`writeLoop()`) pulls from thread-safe queues. No concurrent USB writes are possible.

## 5. TLS Thread Safety (Implemented ✅)

Java's `SSLEngine` is not thread-safe for concurrent `wrap()`/`unwrap()`.

- **Requirement:** Encrypt and decrypt must not overlap
- **Implementation:** `synchronized(tlsLock)` around both `encrypt()` and `decrypt()` in `InBandTls.kt`
- **Note:** This was suspected as the video disconnect root cause but testing showed no effect. Applied anyway as a correctness fix.

## 6. Video Flow Control (Implemented ✅)

The HU sends `VideoAck` (0x8004) for each frame consumed.

- **Requirement:** Track unacked frames. If `max_unacked` (from CONFIG response) is reached, pause sending.
- **Implementation:** Counter tracks unacked frames. In practice, never reaches the limit (100) — disconnect happens at 30-60 unacked.
- **Note:** Flow control is not the disconnect cause, but must be implemented for correctness.

## 7. USB Power Management (Unverified ❓)

Android may suspend the USB connection via Doze mode or thermal throttling.

- **Requirement:** Hold appropriate wake locks to prevent USB suspend
- **Current:** `PARTIAL_WAKE_LOCK` held during connection
- **Question:** Is this sufficient? The official app may use `FULL_WAKE_LOCK` or request USB-specific power management. The inconsistent disconnect timing (9s to 93s) could match OS-level power events.

## 8. Unopened Channels (Unverified ❓)

The HU advertises 9 channels in SERVICE_DISCOVERY_RESPONSE. We open 5 (video, input, audio, sensor, BT).

- **Question:** Does the HU expect all advertised channels to be opened? After a timeout, does it disconnect?
- **Mitigation:** Open all advertised channels, even if we don't use them.

## 9. H.264 Baseline Profile (Fixed ✅)

- **HUIG Requirement:** "H.264 contains only I frames and P frames (no B frames, buffering set to minimum)"
- **Problem:** MediaCodec without explicit profile constraint may emit Main/High profile with B-frames on some devices. B-frames require reordering buffers that the HU's Baseline-only decoder doesn't have.
- **Fix:** Explicitly set `KEY_PROFILE = AVCProfileBaseline` and `KEY_LEVEL = AVCLevel31` on the encoder format.
- **Status:** Fixed 2026-06-04.

## 10. Event-Driven Video (Investigation ❓)

- **HUIG Requirement:** "The AAP protocol sends video frames only for projection UI updates (and does not send frames when no updates are available)"
- **Current:** Test pattern sends frames continuously at fixed FPS regardless of content changes.
- **Question:** Does the HU enforce this? The official app likely sends 1-5fps average (only on UI change). Our 15-30fps constant stream is unusual but the HU has flow control (ACKs) to handle it. Lower FPS = longer survival, which is consistent with either buffer exhaustion OR the HU not expecting continuous data.
- **Note:** During active phone use (scrolling, animations), frames would be sent continuously anyway. This may only matter for the test pattern.

## Disconnect Signature

When the HU decides to drop the connection, the pattern is always:

1. Everything normal (pings, ACKs, sensor data flowing)
2. HU suddenly stops **all** communication (no pings, no ACKs, no sensor data)
3. ~1 second of silence
4. USB EIO on phone

There is never a graceful BYEBYE/SHUTDOWN. This suggests an internal crash or forced endpoint shutdown, not a protocol-level disconnect.

## Environment

- Phone: Motorola Moto G52 (Android 14)
- Head unit: Dacia MediaNav (2019 SEAT Ateca LG unit)
- Protocol: AAP v1.7 over USB AOA
- TLS: TLSv1.2 (phone as server)
