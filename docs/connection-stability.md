# Connection Stability — Android Auto phone-side

Findings from building and debugging an open-source **phone-side** Android Auto
implementation (`org.openandroidauto`, replacing Google's `gearhead` APK) against
a real head unit.

**Environment** (all timings in this doc refer to it unless stated):
- Phone: Motorola Moto G52 (Android 14, Qualcomm `dwc3` USB)
- Head unit: Dacia MediaNav (2019 SEAT Ateca LG unit)
- Link: USB AOA, phone as accessory · AAP v1.7 · TLS 1.2 (phone as server)
- Logs: `/sdcard/Android/data/org.openandroidauto/files/logs/aa_log_*.txt`
- Emulator: openauto (Docker) — stable indefinitely

> History: this doc absorbed the former `bugfix.md` (deleted 2026-10-14). It was
> a chronological debugging diary that repeatedly declared the video disconnect
> "fixed" and re-opened it. Restructured 2026-10-19 into status-ordered sections.

---

# Part 1 — Protocol requirements (what the HU expects)

Each item is a rule we learned the hard way, with its implementation status.

## 1.1 Control channel pings ✅
HU and phone exchange `PingRequest`/`PingResponse` on channel 0, ~1 Hz.
Phone must answer immediately. Priority write queue ensures ping responses are
never queued behind video. Delay >3-5 s risks a drop.
**Status:** implemented; 100% answered in every logged session.

## 1.2 AUTH_COMPLETE must not be sent to the HU ✅
`AUTH_COMPLETE` flows **HU → phone only**. Sending it back triggers
`MESSAGE_UNEXPECTED_MESSAGE` (0x00FF) and puts the HU into an error state.
**Symptom:** idle disconnect at ~8.5 min, video at ~51 s.
**Fixed** 2026-05-30 — 30+ min stable afterwards. Confirmed no 0x00FF since.

## 1.3 Write serialization ✅
All channels multiplex over one USB bulk endpoint. Encapsulation
(`[ch][flags][len][payload]`) must be written atomically. Interleaved writes
desync the HU's parser.
**Status:** single-writer coroutine (`writeLoop()`) pulling from thread-safe queues.

## 1.4 TLS thread safety ✅
`SSLEngine` is not safe for concurrent `wrap()`/`unwrap()`.
**Status:** `synchronized(tlsLock)` around `encrypt()`/`decrypt()`. Suspected as
the root cause once; testing showed no effect on the disconnect. Kept as a
correctness fix.

## 1.5 Video flow control — `AV_MEDIA_ACK` is a credit window ✅
The `0x8004` message on the video channel is `AV_MEDIA_ACK_INDICATION`:

```proto
message AVMediaAckIndication { required int32 session = 1; required uint32 value = 2; }
```

`value` is a **credit/burst count**, not a per-frame ACK. The HU granted
`max_unacked = 100` in its SETUP_RESPONSE (`80 03 08 02 10 64 18 00`).
We track unacked frames and pause at the limit.
**Status:** implemented. Never reached in practice — **0 backlog at every death**.
Flow control is *not* the disconnect cause. (Open question: should we honour
`value` as a real window / ACK the HU's own stream? See §3.6.)

## 1.6 H.264 Baseline Profile ✅
HUIG: "H.264 contains only I and P frames (no B frames)."
**Status:** `KEY_PROFILE = AVCProfileBaseline`, `KEY_LEVEL = AVCLevel31` set explicitly.

## 1.7 Video config negotiation — ⚠️ SUSPECT, NEEDS REWORK
HUIG describes: MD requests configs → HU returns a prioritised list → MD sends
`Start` with the selected index.

The **proto disagrees** with that prose:

```proto
// AVChannelSetupResponseMessage (0x8003, HU → us)
required AVChannelSetupStatus media_status = 1;
required uint32 max_unacked = 2;
repeated uint32 configs = 3;        // a LIST
// AVChannelSetupRequestMessage (0x8000, us → HU)
uint32 config_index = 1;            // WE choose and send this
```

Known problems in our code (2026-10-19, now visible thanks to full logging):
- `sendSetup()` sends `08 03` — i.e. claims **config index 3** — instead of the
  index of the config we actually advertise.
- `sendStart()` sends a config index in field 2, but the proto says that is a
  **session id**.
- We advertise exactly one config (`_480p` @ 30 fps) but read only a single
  value from the HU's `repeated configs` field.
- Observed: HU replied `configIndex=0`; we echoed `0`; screen displayed fine —
  so it is tolerant, but we are not compliant.

**Status:** 🔴 needs rework from the proto (not HUIG prose). See §4.

## 1.8 Video focus must be yielded on revocation — ✅ FIXED 2026-10-19
The HU owns the display and signals via `VIDEO_FOCUS_NOTIFICATION` (0x8008):
`FOCUSED=1`, `UNFOCUSED=2`.
**Bug:** we ignored `UNFOCUSED`. When the HU took the screen (reversing camera)
we kept streaming H.264 into it → **visible digital corruption of the reversing
image**. Reproduced repeatedly 2026-10-19; does not happen without our app.
**Fix:** `sendVideoFrame()` is gated on a `videoFocusHeld` flag — frames are
suppressed immediately on `UNFOCUSED` and resume on `FOCUSED`. Suppressed-frame
count is logged (`focusSuppressed=`).

## 1.9 Event-driven video ✅ IMPLEMENTED 2026-10-19
HUIG: *"the AAP protocol sends video frames only for projection UI updates (and
does not send frames when no updates are available)"*, plus: the HU reports its
decoder pipeline depth so the MD can send *enough idle frames*.
**Bug:** we streamed a constant ~15 fps forever, even on a static screen.
**Fix:** test-pattern loop only posts on content change, plus a 2 s idle frame
for decoder depth. UI toggle, persisted.
**Measured effect:** 14.8 fps → **0.9 fps**, 5.8 KB/s → **0.5 KB/s** (~10×).
**Status:** implemented and verified; correlation with survival is suggestive
but not yet proven (see §2.3).

## 1.10 USB power management — ruled out as cause ❌
Wake locks, `FLAG_KEEP_SCREEN_ON`, battery-optimisation exemption were all added
on the theory that Android was suspending USB.
**Disproven 2026-06-06:** a foreground-only run still disconnected, and `dmesg`
showed the **HU dropping VBUS**. The phone is a passive victim.

## 1.11 Unopened channels ❓
HU advertises 9 channels; we open 5 (video, input, audio, sensor, BT).
Unknown whether the HU expects all to be opened. Low priority.

---

# Part 2 — The disconnect: current understanding

## 2.1 The two distinct failure signatures

**A. HU-initiated drop (the original bug).**
1. Everything normal (pings, ACKs, sensor data flowing)
2. HU stops **all** communication — no pings, no ACKs
3. ~1.4 s of silence while the phone keeps writing
4. HU **physically drops VBUS** — kernel evidence:
   ```
   sgm4154x-charger 1-003b: Vbus removed, disable charge
   msm-dwc3 4e00000.ssusb: vbus:0 event received
   msm-dwc3 4e00000.ssusb: dwc3_otg_start_peripheral: turn off gadget
   ```
5. USB EIO on the phone
No BYEBYE/SHUTDOWN ever. `vbus:0` proves a deliberate HU-side power reset.

**B. Abrupt EIO with no preceding silence.** Last inbound frame is within normal
cadence (hundreds of ms) of the EIO. This is the signature of the **cable being
physically removed**, not the HU dropping us. Detect by measuring the
last-inbound → EIO gap: **> ~1200 ms ⇒ A; < ~1200 ms ⇒ B**.

> This distinction matters and was repeatedly misread. Several sessions
> previously logged as "HU dropped it" were manual cable pulls.

## 2.2 Detection method
```
gap = (time of EIO) - (time of last "RAW ←" line)
gap > 1200 ms  → HU-initiated drop (signature A)
gap < 1200 ms  → abrupt / cable pull (signature B)
```

## 2.3 Evidence log

### Video disconnect, original build (constant-rate video)
| When | Duration | End |
|---|---|---|
| 2026-05-30 | 30+ min | manual (best ever, once) |
| 2026-05-31 | 19 s - 88 s | HU drop |
| 2026-06-05 | 7-9 s (audio on), 74-86 s (foreground) | HU drop |
| 2026-10-14 | 29 s, 49 s, 83 s | HU drop (VBUS confirmed) |
| 2026-10-18 15:55 | 82 s | HU drop (VBUS) |

Duration never correlated with bandwidth: 5 KB/s failed, 170 KB/s lasted longer.
More data (audio, higher fps) → *faster* death.

### After event-driven video (2026-10-19, new build)
| Session | Duration | End | Notes |
|---|---|---|---|
| 20:08 | 1.6 min | cable pull | short test |
| 20:10 | **15.3 min** | cable pull | HU healthy throughout |
| 20:33 | **12.7 min** | cable pull | HU healthy throughout |

**No HU-initiated drop (signature A) in this batch.** Durations are the best
video results recorded. Caveat: most runs ended because the cable was pulled, so
the absence of drops is partly because the runs were stopped early — a run left
alone for 30+ min is still needed (§4 item 1).

### Idle / no-video sessions
| When | Duration | End |
|---|---|---|
| 2026-10-17 12:37 | **17.6 min** | old reconnect bug (HU healthy) |
| 2026-10-19 18:xx | 7.2 / 9.3 / 6.9 min | mixed; 2 were cable pulls |

## 2.4 Ruled out ❌
Bandwidth · flow control / max_unacked · ping timeout · AUTH_COMPLETE/0x00FF ·
TLS race & sequence corruption · audio focus / flow control / B-frames · codec
config format · Annex B vs AVCC · framing (matches aasdk) · USB cable & port
(official app runs indefinitely) · HU hardware/firmware limit · video-only ·
Android background/Doze/wake locks · phone USB driver bug.

## 2.5 Open hypotheses ❓
1. **HU-side timer/watchdog** independent of what we send. Supported by: same
   time-to-death at 10× less data, and the inability of the official app to
   trigger it. *Leading hypothesis.*
2. **Something structural we still don't replicate** — e.g. the focus handshake
   (§1.8), or a periodic message we never send.
3. **HU enforces a cumulative frame/byte budget.** Weakened: 10× less data did
   not obviously shorten life. Budget counters are now logged to settle it.
4. **Config negotiation non-compliance** (§1.7) tripping a decoder watchdog.

---

# Part 3 — Reconnect & restart behaviour

## 3.1 Root causes found (all fixed)
1. **No terminal state published.** On failure the service never set
   `ERROR`/`DISCONNECTED`, so `MainActivity.serviceStarted` stayed `true` forever
   and no new connection was possible → **"must close and reopen the app"**.
2. **No retry.** `START_NOT_STICKY` plus no in-place reconnect.
3. **`readLoop` cancellation deadlock.** `cancelAndJoin()` on a coroutine parked
   in a **blocking native USB read** never returns — cancellation is cooperative
   and cannot interrupt it. Caused multi-minute hangs.
4. **`transport` assigned too late.** It was set only *after* the
   transport-acquisition block returned, but `connect()` runs *inside* it — so
   during a blocking `connect()` the watchdog's `transport?.disconnect()` was a
   **no-op** and could not unblock anything.
5. **`accept()` blocked indefinitely** (no timeout). Combined with (4), it blocked
   for **12 minutes**, then held port 5277 → **`EADDRINUSE`** on the next attempt.

## 3.2 Fixes
- `signalDisconnected()` publishes `ERROR` on every terminal path; MainActivity
  re-arms the Start button and auto-start on it.
- `sessionWithReconnect()` retries in-place (2 s backoff) while the accessory is
  still attached; stops cleanly when it is gone. Re-plug restarts via
  `USB_ACCESSORY_ATTACHED` with **no app kill**.
- **Blocking calls are made interruptible by closing the handle**:
  `disconnect()` closes the fd/stream/socket under a lock so an in-flight
  `read()` throws and returns. `teardownSession()` closes the transport **first**,
  then cancels and joins with a **2 s bound**.
- `transport` is assigned **before** `connect()`.
- `TcpServerTransport.connect()` polls `accept()` with `SO_TIMEOUT=500 ms` up to a
  **10 s deadline**, checking coroutine cancellation.
- Session coroutine failures are caught (not left uncaught — that crashed the
  process) and recorded in `sessionEndReason`.

## 3.3 Known remaining wart
The TCP/DHU fallback (`localhost:5000`, then TCP server on 5277) exists for the
openauto **emulator** but is still attempted in the car. On USB failure that pulls
us onto this path needlessly. **Not yet gated.** See §4 item 4.

---

# Part 4 — TODO

Ordered by value.

1. **🔴 Leave a session alone to conclusion.** Every event-driven run has been
   ended by hand. We need one uninterrupted 30+ min run to know whether
   signature-A drops are actually gone. *Highest value: it is the only test that
   answers the main question.*
2. **🔴 Rework video config negotiation from the proto** (§1.7): send the real
   `config_index` in SETUP; stop putting it in START; parse the repeated
   `configs` list.
3. **🔴 Verify the focus fix on hardware** (§1.8) — reversing camera should no
   longer corrupt. Check `focusSuppressed=` appears in the log on reverse.
4. **🟡 Gate the TCP/DHU fallback** so it isn't attempted on a real device
   (§3.3). USB-only by default, fallback behind a setting.
5. **🟡 Test real MediaProjection** instead of the static test pattern — event
   capture with genuine (but sparse) UI changes.
6. **🟡 Send `VIDEO_FOCUS_REQUEST` (0x8007) after connect** — we never do.
7. **🟡 Decode the remaining sensor batch contents.** We now log GPS; confirm what
   else the ~1 Hz ch=6 batch carries (§5.3).
8. **🟡 Log the negotiated TLS cipher suite** — rule out a cipher/size difference
   from the official app.
9. **⏸ Capture official-app USB traffic** (analyser/MITM) — the definitive diff.
10. **⏸ Open all 9 advertised channels** (§1.11).
11. **⏸ Sensor telemetry expansion** (§5.4).

---

# Part 5 — Sensors & telemetry

## 5.1 Transport
Sensor channel (advertised as ch6 by this HU; `ServiceDiscoveryBuilder` hardcodes
`channel_id=4` — a latent bug). Message IDs: `SENSOR_START_REQUEST=0x8001`,
`SENSOR_START_RESPONSE=0x8002`, `SENSOR_EVENT_INDICATION=0x8003`.

Subscription: `SensorStartRequestMessage { sensor_type=1, refresh_interval=2 }`.

## 5.2 What we subscribe to
Only **3 of ~21** types — `requestDefaultSensors()`: `LOCATION(1)`,
`NIGHT_MODE(10)`, `DRIVING_STATUS(13)`.

## 5.3 What this HU actually sends (confirmed 2026-10-19)
| Type | Name | Observed | Content |
|---|---|---|---|
| 1 | `LOCATION` | ~1 Hz, continuously | lat/lon, accuracy, speed — **decoded** |
| 10 | `NIGHT_MODE` | on change | 0/1 |
| 13 | `DRIVING_STATUS` | at start / change | `0`=UNRESTRICTED, `9`=NO_VIDEO\|NO_CONFIG |

Example decoded line:
```
AASensor: Location: 50.6768736, -2.2340971 acc=0.617m speed=3.545m/s
```
**The phone does receive usable live GPS from this HU.**

Nothing else (speed/RPM/fuel/gear/…) was ever observed from this unit.

## 5.4 Protocol capability vs. reality
The protocol supports ~21 types (speed, RPM, odometer, fuel, gear, parking brake,
HVAC, doors, lights, accelerometer/gyro, environment…). A MediaNav-class HU
generally does not bridge CAN powertrain data into AA, so most will likely never
arrive regardless of subscription. Testing = subscribe and see.

**Known code issues:** live `channel/SensorChannel.kt` invents a non-existent
`TOLL_CARD=22` and `TIRE_PRESSURE`, and has field-number mismatches vs the protos
(e.g. `parseEnvironment`); `sensor/SensorChannel.kt` is dead code.

---

# Part 6 — Instrumentation & tooling

## 6.1 Logging (fixed 2026-10-19)
**Bug:** only `ProjectionService` wrote to `FileLogger`; ~83 log calls across
`ProtocolEngine`, `VideoChannel`, `SensorChannel`, `InBandTls`,
`AaBluetoothServer`, `TcpServerTransport`, `MainActivity` went to **logcat only**
— which rotates within minutes and is gone by the time logs are pulled. This
made protocol/TLS/video state-machine behaviour invisible and blocked several
diagnoses.
**Fix:** `FileLogger` gained `Log`-compatible `w/e/d/i` helpers writing to **both**
logcat and the session file. All classes converted.

## 6.2 Current log lines
- `RAW ← N bytes [gap=Xms]` — inbound frame + silence gap before it.
- `AAProtocol:` — version negotiation, `PING_RESPONSE echoed=`, state transitions.
- `AATls:` — handshake status, wrap/unwrap sizes.
- `AAVideo:` — `Sent SETUP`, `CONFIG received`, `Sent START`,
  `VIDEO_FOCUS_NOTIFICATION: mode=`, codec config, flow control.
- `AASensor:` — decoded sensor values.
- `HEALTH:` every 5 s — pings, silence, backlog, inbound/ack histograms.
- `HEALTH video:` — frames/bytes sent vs acked, unacked, `focusSuppressed`.
- `PING:` / `STALL:` — warnings when the HU goes quiet (2 s threshold; the old
  500 ms threshold false-positived against the ~790 ms idle cadence).
- `PONG:` — ping RTT.
- `TEARDOWN:` — final silence, backlog, ping/pong totals, last inbound, budget.

## 6.3 Disconnect analysis recipe
```bash
# signature A vs B
gap = EIO_time - last_"RAW ←"_time
gap > 1200ms → HU drop   |   gap < 1200ms → cable pull
# budget
grep "HEALTH video" log | tail
# focus (reversing camera)
grep "VIDEO_FOCUS\|focusSuppressed" log
```

---

# Part 7 — External research (2026-10-18)

**No public report of this failure was found.** Searched GitHub, GitLab, Stack
Overflow, XDA, Reddit, Google Groups for a head unit physically dropping VBUS
due to *phone-side* AA video data. Nothing matches.

- **Every major OSS AA project is head-unit-side** (the inverse of us): f1xpl/aasdk,
  opencardev/openauto, harryjph/android-auto-headunit, andreknieriem/headunit-revived,
  mikereidis/headunit — so none would hit this.
- **Closest adjacent:** `tomasz-grobelny/AACS` emulates the *phone* role.
  #12 transfer failures while streaming; #13 had to add ping/pong; **#32 real HUs
  silently ignore a valid-but-unofficial sender** — suggesting HU-side verification
  beyond TLS.
- **HUIG is silent** on watchdogs, VBUS, and any phone-side keepalive cadence
  (grepped in full).
- Kernel `vbus:0` / `turn off gadget` strings are **generic** Qualcomm gadget
  teardown logging, not AA-specific — a symptom, not a cause.
- Notably the HUIG *does* acknowledge the risk: the HU "SHOULD equip USB
  current-limiting and adequate power supply to **avoid VBUS shutdown**".

**Caveat:** search ran on DuckDuckGo only; absence of evidence is weaker than usual.
