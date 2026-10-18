# Connection Stability Requirements

Lessons learned from debugging HU disconnects. The head unit (HU) will tear down the USB session if the phone-side implementation violates any of these requirements.

> **Merged 2026-10-14.** This document now absorbs the former `bugfix.md` (deleted) — it was a chronological debugging log whose still-relevant findings are folded in below as [§12 Evidence log](#12-evidence-log-2026-05--10) and [§13 Ruled out / open hypotheses](#13-ruled-out--open-hypotheses).

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
- **Status:** Fixed 2026-06-04.
- **⚠️ Caveat:** Audio silence **accelerates** the disconnect (sessions dropped from 9-93s to 7-9s when audio was reintroduced). It is correct per HUIG but is an accelerant, not the cause. Audio silence is currently **disabled** for stability testing.

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

The HU sends `AV_MEDIA_ACK_INDICATION` (0x8004) as a credit/window grant — see §11, which corrects the original "one ACK per frame" model here.

- **Requirement:** Track unacked frames. If `max_unacked` (from CONFIG/SETUP_RESPONSE) is reached, pause sending.
- **Implementation:** Counter tracks unacked frames. In practice, never reaches the limit (100) — disconnect happens with near-zero backlog.
- **Note:** Flow control is **not** the disconnect cause (confirmed 2026-10-14: 0 backlog, ~49 kbps at death), but must be implemented for correctness.

## 7. USB Power Management (Ruled out as cause ❌)

Android may suspend the USB connection via Doze mode or thermal throttling.

- **Implementation:** `PARTIAL_WAKE_LOCK` held during connection; `FLAG_KEEP_SCREEN_ON` on the Activity; `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.
- **Status:** These were added on the theory that background/Doze was the cause. **Disproven 2026-06-06** — a foreground-only run still disconnected at 74s, and kernel `dmesg` showed the **HU dropping VBUS** (see §12). The phone is a passive victim; wake locks cannot prevent an HU power cut.

## 8. Unopened Channels (Unverified ❓)

The HU advertises 9 channels in SERVICE_DISCOVERY_RESPONSE. We open 5 (video, input, audio, sensor, BT).

- **Question:** Does the HU expect all advertised channels to be opened? After a timeout, does it disconnect?
- **Mitigation:** Open all advertised channels, even if we don't use them.

## 9. H.264 Baseline Profile (Fixed ✅)

- **HUIG Requirement:** "H.264 contains only I frames and P frames (no B frames, buffering set to minimum)"
- **Problem:** MediaCodec without explicit profile constraint may emit Main/High profile with B-frames on some devices. B-frames require reordering buffers that the HU's Baseline-only decoder doesn't have.
- **Fix:** Explicitly set `KEY_PROFILE = AVCProfileBaseline` and `KEY_LEVEL = AVCLevel31` on the encoder format.
- **Status:** Fixed 2026-06-04.

## 9b. Video resolution negotiation — we advertise one thing, encode another (TODO 🔴)

**HUIG says (Integrating Video / Selecting Video Resolution):**
- "During initial service discovery, the MD and HU negotiate the screen resolution,
  density, pixel aspect ratio (PAR), and maximum frame rate... The MD renders and
  encodes content in the negotiated configuration (**no upscaling or downscaling
  required**)."
- Negotiation flow: (1) MD requests HU's `VideoConfigurations` in service discovery;
  (2) **HU sends a Config Message with a prioritised list of indices** (most
  preferred first); (3) MD selects based on HU preference + encoder capability;
  (4) MD sends `Start` **with the index of the selected configuration**.
- Required baselines: "**800x480 @ 30 FPS**" (H.264 BP level 3.1 REQUIRED for both
  sides); "Any AAP HU integration MUST support **480p at 30 FPS** as the minimum
  default." "Using non-standard codec resolutions can result in significant
  performance loss."

**What we do (wrong):**
- `ServiceDiscoveryBuilder.kt:79-80` advertises `buildVideoConfig(2,...)` = 720p/30
  and `buildVideoConfig(1,...)` = 480p/30 — **neither of which we encode**.
- `VideoConfig` default is **800×480 @ 15 fps**; `VideoChannel.startEncoding()` builds
  the `MediaFormat` from that default, so we encode 800×480/15 regardless.
- `VideoChannel.sendStart()` hardcodes `session=1, config=0` — we do **not** parse the
  HU's prioritised config list (`0x8003` CONFIG carries `config_index` in field 3); we
  just always claim index 0.
- Net: we declare 720p/480p at 30 fps and deliver 800×480 at 15 fps. The HU negotiated
  in bad faith on our side. Plausible trigger for a decoder-pipeline watchdog.

**TODO:**
1. Advertise exactly one config we can actually produce: **800x480 @ 30 fps** (the
   spec's guaranteed baseline), and drop the 720p entry until we can encode it.
2. Parse the HU's `0x8003` CONFIG `config_index` (field 3) and echo that index in the
   `0x8001` START (field 2) instead of hardcoding 0.
3. Encode at the negotiated resolution — build `VideoConfig` from the selected entry,
   not from the 800×480/15 default. (15→30 fps is part of this; see §10 on cadence.)

## 10. Event-Driven Video (TODO 🔴 — HUIG requires it)

**HUIG requirement (verbatim, Integrating Video):**
> "To preserve bandwidth and power, the AAP protocol sends video frames **only for
> projection UI updates** (and does not send frames when no updates are available).
> However, HU video decoders MUST avoid adversely affecting latency by not entering
> power-saving mode even when not receiving frames."

And on pipeline depth:
> "Because the MD does **not render continuously**, the HU MUST report the depth of
> its decoder pipeline so the MD can send **enough idle frames** to ensure the display
> updates properly."

So the contract is: **send a frame only when the screen changes**, plus periodic
*idle* frames to honour the HU's reported decoder depth. A constant 15 fps flood is a
spec violation, not merely "unusual".

**What we do (wrong):** `VideoChannel` runs a fixed-fps loop and emits a frame every
~66 ms forever, regardless of content change. Measured on the 2026-10-18 15:55 run:

```
frames: 1226   gap ms: min=34  p50=66  p90=70  max=79   (zero gaps >200 ms)
sizes: 1110 frames <100 B (near-identical static P-frames), avg 400 B, ~47 kbps
```

i.e. ~15 fps of mostly-identical frames for 83 s on a *static black* screen — precisely
what the spec says not to do. This fits the "more data → faster death" pattern (idle
10-20 min vs video 9 s-5 min) far better than any bandwidth theory.

**TODO (implemented 2026-10-18):** make encoding event-driven.
1. Only emit a frame when the captured content changes (dirty-region / frame diff), or
   on keyframe interval.
2. Emit periodic *idle* frames at a low cadence to satisfy decoder depth (the HUIG's
   "enough idle frames"), rather than a fixed 30 fps stream.
3. Keep a floor (spec: between max frame rate and **5 FPS**) so the HU decoder does not
   power down.
4. Measure: does time-to-disconnect scale with *frames sent* or with *wall time*? If it
   scales with frames, an event-driven stream should survive far longer on a static
   test pattern.

## 11. AV_MEDIA_ACK Semantics — CREDIT WINDOW, not per-frame ACK (Discovered 2026-10-14 ❓)

**Correction to section 6.** The `0x8004` message on the video channel is
`AV_MEDIA_ACK_INDICATION` (`third_party/aasdk/aasdk_proto/AVChannelMessageIdsEnum.proto`),
defined as:

```proto
message AVMediaAckIndication {   // AVMediaAckIndicationMessage.proto
    required int32  session = 1;
    required uint32 value   = 2;
}
```

Decoding every `0x8004` from today's sessions (`aa_log_2026-10-14_18-03-42.txt`,
873 occurrences) gives an **identical payload every time**: `80 04 08 01 10 01`
= `session=1, value=1`.

- `value` is a **credit/burst count**, not a 1-per-frame ACK. aasdk's head-unit
  side models it as a window (`onAVMediaAckIndication`), and the HU granted
  `max_unacked = 100` in its SETUP_RESPONSE (`80 03 08 02 10 64 18 00` →
  status=READY(2), max_unacked=100, config=0).
- Our `VideoChannel.handleMediaAck()` treats each `0x8004` as "one frame
  consumed" (`unackedFrames--`). Since the HU sends `value=1` continuously, this
  happens to hold only because we send ~1 frame per ack in steady state.

**Why it matters:** the ACK inter-arrival exactly tracks our frame cadence
(p50 = 67ms, max 88ms, matching the 15fps send loop) and the ack stream stops the
instant the HU stalls — so flow control is *healthy* right up to the disconnect
(0 backlog, 49 kbps at death). This reinforces that the disconnect is not
back-pressure. **Open question:** is the HU expecting *us* to send
`AV_MEDIA_ACK_INDICATION` back for its own `value` credit, or to respect the
`value` field as a real window rather than ignoring it? The instrumentation below
logs the decoded `value` so the next run settles this.

## Instrumentation (added 2026-10-14 ❓)

`ProjectionService.kt` now logs the mechanics of the stall so the next session
is directly comparable:

- `RAW ← N bytes [gap=Xms]` — silence gap before every inbound read.
- `HEALTH:` every 5s — `pingSent`, current silence, write backlog
  (`enq=` enqueued-minus-written, `q=` total enqueued, `w=` total written),
  `framesSent`, `framesReceived`; plus inbound/ack type histograms.
- `STALL:` one-shot when the HU is silent ≥500ms while frames are still going out.
- `PING:` warning when a ping goes unanswered for >1.5s (earliest stall signal).
- `PONG:` RTT of the HU's echoed ping timestamp (logged every 5th, or if >500ms).
- `TEARDOWN:` on destroy — final silence duration, backlog, ping/pong totals,
  last inbound channel/type, and inbound/ack histograms.

## Disconnect Signature

When the HU decides to drop the connection, the pattern is always:

1. Everything normal (pings, ACKs, sensor data flowing)
2. HU suddenly stops **all** communication (no pings, no ACKs, no sensor data)
3. ~1.4 seconds of silence while the phone keeps writing
4. HU **physically drops VBUS** (USB power) — kernel: `vbus:0`, `Vbus removed`
5. USB EIO on phone

There is never a graceful BYEBYE/SHUTDOWN. The `vbus:0` event proves this is a
deliberate HU-side power reset, not a protocol-level disconnect and not a
phone-side USB driver fault.

### Confirmed 2026-10-14 (three sessions)

| Session | Start | Duration | Frames sent/recv | HU went silent | VBUS dropped | EIO |
|---|---|---|---|---|---|---|
| `17-41-03` | 17:41:03 | ~29 s | 448 / 445 | 17:41:31.398 | 17:41:32.877 | 17:41:32.912 |
| `17-51-04` | 17:51:24 | ~49 s | 114 / 161 | 17:52:13.1 | — | 17:52:13.575 |
| `18-03-42` | 18:03:42 | ~83 s | 1076 / 1127 | 18:05:04.047 | 18:05:05.425 | 18:05:05.488 |

Kernel evidence around each VBUS drop (from `logcat`):

```
10-14 17:41:32.794  sgm4154x-charger 1-003b: Vbus removed, disable charge
10-14 17:41:32.877  msm-dwc3 4e00000.ssusb: vbus:0 event received
10-14 17:41:32.877  msm-dwc3 4e00000.ssusb: XCVR: BSV clear
10-14 17:41:32.877  msm-dwc3 4e00000.ssusb: dwc3_otg_start_peripheral: turn off gadget

10-14 18:05:05.358  sgm4154x-charger 1-003b: Vbus removed, disable charge
10-14 18:05:05.425  msm-dwc3 4e00000.ssusb: vbus:0 event received
10-14 18:05:05.425  msm-dwc3 4e00000.ssusb: XCVR: BSV clear
10-14 18:05:05.426  msm-dwc3 4e00000.ssusb: dwc3_otg_start_peripheral: turn off gadget dwc3-gadget
```

Note the ordering: the HU stops *responding* (goes deaf) ~1.4s **before** it
cuts VBUS. So the app-side EIO always trails the real trigger by ~1.4s.

### Contrast: 2026-10-17 idle (accessory mode, no projection)

`17:36` of continuous stable operation in accessory mode — see §11c. The HU
never stalled; the session ended only because of the old reconnect bug. This
supports the view that the VBUS reset is triggered by the **video pipeline**
specifically, since the same connection without projection ran 17.5 min clean
(and only ~29-83 s with projection, §Disconnect Signature above).

## 11b. Reconnect & restart behaviour (Investigated 2026-10-17 ✅ FIXED)

Two long-standing usability bugs were root-caused from the 2026-10-17 log:

**Bug A — no reconnect after a drop.** On EIO the service tore down but **never
published a terminal `ConnectionState`**. `MainActivity` only re-arms its
`serviceStarted` flag when it observes `ERROR`/`DISCONNECTED`, so the flag stayed
`true` forever and no further connection was possible until the process was
killed. Additionally the service used `START_NOT_STICKY` and did not retry.

**Bug B — `EADDRINUSE` on restart.** The TCP-server fallback (`TcpServerTransport(5277)`)
created a `ServerSocket` that was only closed in `disconnect()`. When a session
ended without a clean disconnect, the port leaked and the next start failed with
`Status: Error: bind failed: EADDRINUSE`.

**Fixes applied:**
1. `ProjectionService.signalDisconnected(reason)` sets `ConnectionState.ERROR` on
   every terminal path (EIO, EOF, exception). MainActivity then re-arms the Start
   button and auto-start.
2. New `sessionWithReconnect()`: after a session ends, if the USB accessory is
   **still attached** it retries in-place after a 1 s backoff (`resetForReconnect()`
   clears transport, TLS, channels and drains the write queues, keeping state at
   `CONNECTING` so no second service is spawned). If the accessory is gone (the
   HU dropped VBUS) it stops cleanly — and re-plugging now re-triggers
   `USB_ACCESSORY_ATTACHED` → `checkAutoStart()` → restart, with **no app kill needed**.
3. `TcpServerTransport.connect()` closes any leftover `ServerSocket` and binds via
   `ServerSocket(reuseAddress=true).bind(...)` to avoid the port leak.

**Verification:** the 2026-10-17 log shows the old failure mode exactly — at
12:55:23 a fresh `Service starting` immediately hit `EADDRINUSE`, then
`Service destroying` (see §12). The fix is built and installed; a live re-plug
test against the HU is still pending.

## 11c. "Accessory mode, no projection" run — 17.5 minutes (2026-10-17)

The best idle-mode result yet. Session `aa_log_2026-10-17_12-37-46.txt` ran
**12:37:46 → 12:55:23 = 17 min 36 s**, with the HU connected in accessory mode
but **not** running Android Auto projection (no video streamed; state/sensor
traffic only).

- `pingsSent=1049 pongsRecv=1049` — **zero lost pings over 17.5 min**.
- Write backlog `enq=0` throughout; `framesSent=2122` (pings + sensor replies).
- Steady 1 Hz `ch=6 type=0x8003` sensor batches (len 36/37) all session.
- TLS sequence numbers strictly monotonic, no corruption.
- The session did **not** end from an HU stall — it ended when the service was
  restarted (the old reconnect bug, §11b). The HU was still happily responding
  right up to the last line.

**Caveat on the instrumentation:** the `STALL:` line (500 ms threshold) fires
219 times here as a **false positive** — at this low message rate the normal
inter-ping gap is ~790 ms, so silence routinely exceeds 500 ms without any
problem. The threshold should be raised (or made cadence-aware) before it is
useful as a warning. The `HEALTH`/`TEARDOWN` lines behaved correctly.

## 12. Evidence log (2026-05 → 10)

### What works perfectly
- Full handshake: VERSION(v1.7) → TLS → AUTH → SERVICE_DISCOVERY → CHANNEL_OPEN
- Video SETUP (H264_BP) → CONFIG (STATUS_READY, max_unacked=100) → FOCUS (PROJECTED) → START
- H.264 encoding + HU decode/display (test pattern visible on HU)
- Bidirectional 1 Hz pings, audio channel SETUP/CONFIG, input BINDING_REQUEST
- openauto emulator: connection stable indefinitely
- 2026-05-30: 30+ minutes stable without video (after AUTH_COMPLETE fix)

### Duration vs. configuration (historic)

| Config | Duration | Notes |
|---|---|---|
| 30 fps, 2 Mbps | ~3 s | |
| 15 fps | ~33 s | |
| 10 fps | ~93 s | |
| 15 fps, 250 Kbps, no fragment | 13-27 s | ~5 KB/s — **still disconnects** |
| 10 fps, 2 Mbps | 93 s | ~170 KB/s — lasts *longer* |
| Audio silence enabled | 7-9 s | audio is an accelerant |
| Video, no audio, foreground | 74-86 s | |
| No video (idle) | 8.5 min - 10 min | still disconnects, variable |
| No video, after AUTH_COMPLETE fix | 30+ min | |

**Key finding: data rate does not explain the disconnect.** 5 KB/s disconnects
in seconds while 170 KB/s lasts 93s. More data (audio, higher fps) merely makes
the HU give up sooner.

### 2026-05-31 → 06-07 investigation timeline (condensed)
- Found `headunit-revived` had the same bug; applied TLS `synchronized` lock → **no effect** on video disconnect.
- Applied HUIG v1.3 fixes (audio focus GAIN/wait, no AUDIO_FOCUS_RESPONSE to HU, audio n-ACK flow control, Baseline profile enforced) → **no effect**.
- Disproved the "app backgrounded" theory: foreground-only runs (90s, 240s) died too; earlier "background = death in 5s" was coincidence within the variable window.
- **2026-06-06:** foreground-only run died at 74s with `dmesg` showing HU dropped VBUS → established the HU power-reset root cause.
- Verified our framing matches aasdk exactly (`[ch][flags][len][TLS record]`; FIRST frame carries total length). No framing difference found.

### 2026-10-14 re-confirmation
Three fresh sessions (table in §Disconnect Signature). Everything healthy up to
the stall: all video frames ACKed (873/895), zero write backlog, ~49 kbps at
death, TLS sequence numbers strictly monotonic (`…fc 1d … fc 32`, no corruption).
The stall-then-VBUS-drop signature reproduced exactly.

## 13. Ruled out / open hypotheses

### Ruled out ❌
- **Bandwidth overflow** — 5 KB/s disconnects; official app does 30fps fine.
- **Flow control / max_unacked** — never reached; 0 backlog at death (2026-10-14).
- **Ping timeout** — pings answered correctly via priority queue.
- **AUTH_COMPLETE / 0x00FF** — fixed; not the video disconnect.
- **TLS race / sequence corruption** — synchronized; seq monotonic in logs.
- **Audio focus / audio flow control / B-frames** — all corrected, still disconnects.
- **Codec config format; Annex B vs AVCC** — both work; HU decodes fine.
- **Framing** — matches aasdk exactly.
- **USB cable/port** — official app runs 10+ min on the same cable.
- **HU hardware/firmware limitation** — official app proves sustained video works.
- **Video-only** — idle sessions disconnect too (variable 2-10 min).
- **Android background / Doze / wake locks** — foreground-only runs die; HU cuts VBUS.
- **Phone USB driver bug** — phone logs show it is the passive victim of VBUS removal.

### Open hypotheses ❓
1. **What HU-internal state triggers the VBUS reset?** Decoder error counter, buffer management failure, watchdog expecting a specific cadence, or TLS-record-size pattern. The 2026-05-30 30-min success may have been a different HU state.
2. **Is our video payload structurally different from the official app's?** Candidates: message type (we use `0x0000 AV_MEDIA_WITH_TIMESTAMP`), timestamp epoch (we use zero-based micros), SPS/PPS refresh cadence, or an expected protobuf envelope around the H.264 data.
3. **Do we need to honour/return `AV_MEDIA_ACK` credit?** See §11 — we ignore the `value` field entirely.
4. **TLS cipher suite** — if our negotiated cipher differs from the official app's, record sizes/patterns differ; could confuse HU buffer management. Worth logging the negotiated suite.
5. **Unopened channels** — we open 5 of 9 advertised; see §8.

### Next concrete tests
1. **Capture official-app USB traffic** (USB analyzer / MITM) and diff against ours — the definitive experiment.
2. **1 fps video** — if 15fps/74s → 1fps should last ~18 min if it is purely per-frame accumulation.
3. **Log the negotiated TLS cipher suite.**
4. **Decrypt the ~1 Hz sensor batches** — the logger currently skips TLS records >10 bytes (`routeMessage`), so the HU's actual sensor payloads are invisible; see §14.

## 14. Sensor / telemetry availability (2026-10-14 ❓)

The protocol supports ~21 `SensorType` values (speed, RPM, fuel, gear, parking
brake, odometer, HVAC, doors, lights, accel/gyro, GPS, environment, …), but
**this HU only pushes two in plaintext**:

- `NIGHT_DATA` (type 10) — `is_night` 0/1.
- `DRIVING_STATUS` (type 13) — bitmask; observed `0` (UNRESTRICTED) and `9` (NO_VIDEO|NO_CONFIG).

Plus a **~1 Hz encrypted batch on ch6** (alternating 25/36-byte TLS records) that
the logger does **not** decrypt (size-cap), so its contents are unknown.

- We subscribe to only **3 of ~21** types (`requestDefaultSensors()`: LOCATION, NIGHT_MODE, DRIVING_STATUS). Subscribe via `SensorStartRequest` (0x8001), proto `SensorStartRequestMessage { sensor_type=1, refresh_interval=2 }`.
- MediaNav-class HUs generally do not bridge CAN powertrain data into AA, so
  speed/RPM/fuel/gear may never arrive regardless of subscription.
- **Known code issues:** live `channel/SensorChannel.kt` invent a non-existent
  `TOLL_CARD=22` and `TIRE_PRESSURE`, and has field-number mismatches vs the
  protos (e.g. `parseEnvironment`); `sensor/SensorChannel.kt` is dead code.
  `ServiceDiscoveryBuilder` hardcodes the sensor `channel_id` to 4 while runtime
  assigns 6.

## 15. External research — is this a known bug? (2026-10-18)

**Verdict: no public report of this failure.** Searched GitHub, GitLab,
Stack Overflow, XDA, Reddit and Google Groups for a head unit physically
dropping VBUS due to *phone-side* AA video data. Nothing matches.

- **Every major OSS AA project is head-unit-side** (the inverse of us):
  f1xpl/aasdk, opencardev/openauto, harryjph/android-auto-headunit,
  andreknieriem/headunit-revived, mikereidis/headunit. Their issue trackers do
  not contain this symptom — expected, since they do not implement the phone role.
- **Closest adjacent:** `tomasz-grobelny/AACS` emulates the *phone* role.
  - #12 "transfer failed: -7" — bulk-transfer failure while streaming (closest
    public symptom; no VBUS drop).
  - #13 — had to add PingRequest/PingResponse keepalive.
  - #32 — real head units silently ignore a valid-but-unofficial sender even when
    a dev HU (DHU) accepts it. Suggests HU-side verification beyond TLS.
- **HUIG is silent** on watchdogs, VBUS, and any phone-side keepalive cadence
  (grepped the full text).
- **Kernel strings are generic**: `vbus:0`/`turn off gadget` are normal
  Qualcomm `dwc3` gadget-teardown logging once the host removes VBUS — they are
  a *symptom*, not a cause, and are not AA-specific.

**Caveat:** search ran on DuckDuckGo only (other backends returned errors), so
absence of evidence is weaker than usual. But nothing found contradicts our data.

**Notably, the HUIG itself acknowledges VBUS shutdown as a known risk:** the HU
"SHOULD equip USB current-limiting and adequate power supply to **avoid VBUS
shutdown**". So the HU cutting VBUS under load is a recognised failure mode —
the question remains what our stream does to provoke it.

## 16. Consolidated TODO / open experiments

Ordered by promise (see §9b, §10 for detail):

1. **🔴 Fix resolution negotiation** (§9b) — advertise only 800×480 @ 30 fps,
   parse the HU's prioritised `config_index`, and encode at the selected config
   instead of the 800×480/15 default. *(implemented 2026-10-18)*
2. **🔴 Event-driven video** (§10) — send frames only on UI change plus periodic
   idle frames at the HU's decoder depth; honour the 5-30 fps floor. *(implemented
   2026-10-18)*
3. **🔴 Ack-budget instrumentation** — log cumulative frames/bytes accepted by the
   HU vs time-to-disconnect, to test whether the HU enforces a frame/byte budget
   (§ "more data → faster death"). *(implemented 2026-10-18)*
4. **⏸ Send `VIDEO_FOCUS_REQUEST` (0x8007) after connect** — we never do; HUIG
   describes MD-requested video focus. Deferred.
5. **⏸ Log the negotiated TLS cipher suite** — rule out a cipher/size difference
   from the official app.
6. **⏸ Capture official-app USB traffic** (analyser/MITM) — the definitive diff.
7. **⏸ Open all 9 advertised channels** (§8).
8. **⏸ Test real MediaProjection** (event-driven) rather than the static test
   pattern — the pattern produces artificially uniform traffic.

## Environment

- Phone: Motorola Moto G52 (Android 14)
- Head unit: Dacia MediaNav (2019 SEAT Ateca LG unit)
- USB: standard USB-A cable (AOA, phone as accessory)
- Protocol: AAP v1.7 over USB AOA
- TLS: TLSv1.2 (phone as server)
- Logs: `/sdcard/Android/data/org.openandroidauto/files/logs/aa_log_*.txt`
- Emulator: openauto (Docker) — stable indefinitely
