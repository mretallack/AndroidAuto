# Android Auto Protocol (AAP) — reference for this implementation

Practical reference for the **phone side** of AAP, as implemented here. Written
from the aasdk protos, the Head Unit Integration Guide (HUIG), observed traffic
against a real head unit, and mistakes we have actually made.

This is a *working* document: where the spec is silent or ambiguous, that is
called out explicitly, because those gaps are where bugs hide.

- **Wire/framing, TLS, channel model** → §1–§3
- **Message catalogue per channel** → §4
- **Sensor/pub-sub model and its correlation hazard** → §5
- **Video, focus and flow control** → §6
- **Audio, input, Bluetooth, navigation** → §7–§10
- **Known ambiguities and traps** → §11 ← read this before trusting any log

Authoritative sources:
- Protos: `third_party/aasdk/aasdk_proto/*.proto` (aasdk, GPL-3.0)
- HUIG v1.3: <https://milek7.pl/.stuff/galdocs/huig13_cache.html>
- Ecosystem survey: [`research.md`](research.md)

---

## 1. Framing

Every message is a frame on one of the multiplexed channels:

```
[channelId:1][flags:1][length:2 big-endian][payload:length]
```

`flags` bits (`MessageFramer.FrameHeader`):

| Bit | Meaning |
|---|---|
| `0x03` | frame type: `0x00`=FIRST, `0x01`=LAST, `0x02`=MIDDLE, `0x03`=SINGLE |
| `0x04` | control message |
| `0x08` | payload is encrypted (TLS record) |

**Fragmented messages** use FIRST/MIDDLE/LAST. The FIRST frame carries the
total length:

```
FIRST:  [channel:1][flags:1][chunkLen:2][totalPlaintextLen:4][encrypted:chunkLen]
MID/LAST:[channel:1][flags:1][chunkLen:2][encrypted:chunkLen]
```

Each chunk is a **separate TLS record** — small records keep the HU's reassembly
buffer happy. We fragment at 2 KB (`FRAGMENT_SIZE`).

> **Trap:** our `fragmentEnabled` default is *off*. Fragmentation was once
> blamed for the video disconnect, so the path is less exercised than it looks.

## 2. TLS

- TLS 1.2, **phone is the server** (the HU initiates).
- Handshake is **in-band**: TLS records travel as payload of control-channel
  (`ch=0`) messages of type `SSL_HANDSHAKE (0x0003)`, not as raw bytes on the
  wire.
- After the handshake, set the `0x08` encrypted flag on frames.
- `AUTH_COMPLETE (0x0004)` flows **HU → phone only**. Sending it back triggers
  `MESSAGE_UNEXPECTED_MESSAGE (0x00FF)` and eventually a disconnect. See
  `connection-stability.md` §1.2.

**Thread safety:** `SSLEngine` is not safe for concurrent `wrap()`/`unwrap()`.
We hold a single lock across encrypt/decrypt.

> **Known unexplained failure:** a 72-minute run ended with
> `SSLV3_ALERT_BAD_RECORD_MAC` (record type `0x15` = alert) after ~4300 pings /
> ~35k sensor batches. Cause unknown. `connection-stability.md` §2.6.

## 3. Channel model

Channels are numbered by the HU in `SERVICE_DISCOVERY_RESPONSE`. **Do not
hardcode channel numbers** — parse the response and map by capability:

| Channel | Capability | Typical id (this HU) |
|---|---|---|
| Control | — | 0 (fixed) |
| Video | `av=true`, `stream_type=VIDEO(3)` | 1 |
| Input | `input=true` | 2 |
| Audio out | `av=true`, `stream_type=AUDIO(1)` | 3 |
| Audio in | `av_input=true` | 4 |
| Sensor | `sensor=true` | 6 |
| Bluetooth | `bt=true` | 7 |
| Navigation | `nav=true` | 8 |

> **Trap:** `ServiceDiscoveryBuilder` hardcodes the sensor `channel_id` to 4
> while runtime assigns 6. Harmless so far but wrong.

Control-channel message ids (`ControlMessageIdsEnum.proto`) are implemented
exactly; see `ProtocolEngine.ControlMessageType`.

---

## 4. Control channel (ch 0)

| Id | Message | Direction |
|---|---|---|
| `0x0001/0x0002` | VERSION_REQUEST / RESPONSE | both |
| `0x0003` | SSL_HANDSHAKE | both (in-band TLS) |
| `0x0004` | AUTH_COMPLETE | **HU → phone only** |
| `0x0005/0x0006` | SERVICE_DISCOVERY_REQUEST / RESPONSE | both |
| `0x0007/0x0008` | CHANNEL_OPEN_REQUEST / RESPONSE | both |
| `0x000B/0x000C` | PING_REQUEST / PING_RESPONSE | both, ~1 Hz |
| `0x000D/0x000E` | NAVIGATION_FOCUS_REQUEST / RESPONSE | both |
| `0x000F/0x0010` | SHUTDOWN_REQUEST / RESPONSE | both |
| `0x0011` | VOICE_SESSION_REQUEST | HU → phone |
| `0x0012/0x0013` | AUDIO_FOCUS_REQUEST / RESPONSE | phone→HU request; HU→phone **notification** |

**Ping note:** our request carries a timestamp in field 1 (`System.currentTimeMillis() * 1000`, microseconds). The HU echoes it; we log the RTT. Whether the HU
cares about the value is unverified — it has never rejected one.

**Audio focus:** the phone sends `RELEASE(4)` on connect (HUIG: so the HU keeps
default focus). `AUDIO_FOCUS_REQUEST` from the HU is a **notification**, not a
request — do not reply, or you reproduce the AUTH_COMPLETE bug class.

---

## 5. Sensors — and the correlation hazard

### 5.1 Model
Pub-sub. Phone sends `SENSOR_START_REQUEST (0x8001)` per type; HU replies
`SENSOR_START_RESPONSE (0x8002)`; data arrives as `SENSOR_EVENT_INDICATION`
(`0x8003`) batches. There is also `SENSOR_ERROR (0x8004)`.

```proto
message SensorStartRequestMessage  { SensorType sensor_type = 1; int64 refresh_interval = 2; }
message SensorStartResponseMessage { Status status = 1; }   // ← NO sensor_type
```

`SensorEventIndication` fields are repeated and numbered **by SensorType value**,
so `field N == SensorType N`. That is how batches are decoded.

### 5.2 ⚠️ THE CORRELATION HAZARD — read this
**A response cannot be matched to its request by anything other than arrival
order**, because the response carries no sensor type. Consequences:

- If **one** response is lost or reordered, **every subsequent verdict is
  attributed to the wrong type** — silently, and the resulting table looks
  perfectly plausible.
- This actually happened (2026-10-23): 3 responses went unattributed and every
  verdict shifted by three, producing a wrong capability table that then drove a
  wrong code change.

**Mitigation (implemented):** count matches vs. requests. Every response is
logged numbered; an unmatched one logs `UNMATCHED — correlation may have
shifted!`; and `lifecycleSummary()` ends with:

```
correlation=OK|SUSPECT (matched=N unmatched=N pending=N)
```

> **Rule: treat any `SUSPECT` run's per-type verdicts as invalid.** The
> surrounding data (timings, gaps, other channels) is still fine.

### 5.3 Status codes
`StatusEnum` says `OK=0`, `FAIL=1`. **This HU returns `247` for failure.** Be
tolerant of non-standard codes; treat `0` as OK and everything else as failure.

### 5.4 Which types can ever carry data
`SensorTypeEnum` reserves 1–21, but `SensorEventIndication` has **no payload
field** for:
- **18 `TIRE`** — no field
- **21 `GPS`** (satellites) — no field

So those can never deliver, regardless of subscription. We also have an invented
`TOLL_CARD=22` which is not in the enum at all.

### 5.5 This head unit's actual capabilities
Verified 2026-10-24 (clean run, 27 requests / 27 responses, correlation OK):

**Accepted and delivering (7):** `LOCATION`(1), `CAR_SPEED`(3),
`PARKING_BRAKE`(7), `NIGHT_DATA`(10), `DRIVING_STATUS`(13), `ACCEL`(19),
`GYRO`(20)

**Rejected (12):** `COMPASS`(2), `RPM`(4), `ODOMETER`(5), `FUEL_LEVEL`(6),
`GEAR`(8), `DIAGNOSTICS`(9), `ENVIRONMENT`(11), `HVAC`(12), `DEAD_RECONING`(14),
`PASSENGER`(15), `DOOR`(16), `LIGHT`(17)

`acceptedButSilent` was empty — everything accepted delivered.

**Units:** `Speed.speed` is m/s ×1000 (divide by 1000, then ×3.6 for km/h).
`Location.latitude/longitude` are degrees ×1e7. Negative int32 values are
sign-extended to 64-bit varints — `.toInt()` before scaling.

### 5.6 `DEAD_RECONING` is misnamed
Type 14's payload is `SteeringWheel` (steering_angle, wheel_speed), not dead
reckoning. Our enum name is wrong.

---

## 6. Video

### 6.1 Lifecycle
```
CHANNEL_OPEN → SETUP (0x8000) → SETUP_RESPONSE/CONFIG (0x8003)
             → VIDEO_FOCUS_NOTIFICATION (0x8008) → START (0x8001) → media frames
```

Message ids (`AVChannelMessageIdsEnum.proto`):

| Id | Message |
|---|---|
| `0x0000` | `AV_MEDIA_WITH_TIMESTAMP` — `[type:2][timestampUs:8][H.264]` |
| `0x0001` | `AV_MEDIA_INDICATION` — used for codec config |
| `0x8000` | `SETUP_REQUEST` |
| `0x8001` | `START_INDICATION` |
| `0x8002` | `STOP_INDICATION` |
| `0x8003` | `SETUP_RESPONSE` (aka CONFIG) |
| `0x8004` | `AV_MEDIA_ACK_INDICATION` |
| `0x8007` | `VIDEO_FOCUS_REQUEST` |
| `0x8008` | `VIDEO_FOCUS_INDICATION` |

### 6.2 Config negotiation — ⚠️ currently NON-COMPLIANT
The proto says:
```proto
message AVChannelSetupResponse { required Status media_status=1; required uint32 max_unacked=2;
                                 repeated uint32 configs=3; }   // a LIST
message AVChannelSetupRequest  { uint32 config_index = 1; }     // WE choose this
```
So the **phone** picks the config index and sends it in SETUP; the HU returns a
prioritised list. Note this contradicts the HUIG prose, which describes the MD
sending the index in `Start`.

Our current behaviour is wrong on both counts:
- `sendSetup()` sends `08 03` (claiming **config index 3**), not the index of the
  config we advertise.
- `sendStart()` puts a config index in field 2 where the proto expects a
  **session id**.

The HU tolerates it (video displays), but it is not compliant. See
`connection-stability.md` §1.7, TODO item 2.

### 6.3 Focus — must be yielded
`VideoFocusMode`: `NONE=0`, `FOCUSED=1`, `UNFOCUSED=2`.

The HU owns the display. When it sends **UNFOCUSED** (e.g. reversing camera) the
phone **must stop sending frames** or it corrupts whatever the HU is showing.
Our symptom was visible digital corruption of the reversing-camera image.
Implemented: frames are gated on a `videoFocusHeld` flag.

### 6.4 Flow control
`0x8004` `AVMediaAckIndication { session=1; value=2; }`. `value` is a
**credit/burst count**, not a per-frame ACK. The HU granted `max_unacked=100`.
We pause when unacked would exceed it. In practice this never triggers — 0
backlog at every disconnect we have measured, so flow control is *not* the
disconnect cause.

### 6.5 Encoding requirements
H.264 **Baseline Profile**, level 3.1, I+P frames only (no B-frames). HUIG:
800×480 @ 30 fps is the guaranteed baseline. Frames should be sent **only for UI
updates** ("does not send frames when no updates are available"), plus enough
idle frames to satisfy the HU's decoder depth. See `connection-stability.md`
§1.9 — we now do this.

---

## 7. Audio

`SETUP_REQUEST (0x8000)` → CONFIG → stream PCM/AAC. Silence must be streamed
continuously if the channel is open, else the HU may time it out. Requires
`AUDIO_FOCUS(GAIN)` and up to 500 ms wait for the grant before sending audio,
and must stop on focus loss. Audio is n-ACK flow controlled.

> **Observation:** enabling audio silence *accelerates* the disconnect (sessions
> dropped from 9–93 s to 7–9 s). Correct per HUIG but currently disabled by
> default for stability testing.

## 8. Input

`AV_INPUT_OPEN_REQUEST (0x8005)` / RESPONSE (`0x8006`);
`INPUT_EVENT_INDICATION (0x8001)` carries touches/keys;
`BINDING_REQUEST (0x8002)` / RESPONSE (`0x8003`).
Coordinates arrive in head-unit space and must be scaled to phone resolution.

## 9. Bluetooth

Ids from `BluetoothChannelMessageIdsEnum.proto`: `PAIRING_REQUEST=0x8001`,
`PAIRING_RESPONSE=0x8002`, `AUTH_DATA=0x8003`. The phone must send a real BT MAC;
`BluetoothAdapter.getAddress()` returns `02:00:00:00:00:00` on Android 6+, so we
read `Settings.Secure "bluetooth_address"` instead.

> The HU reports `already_paired=true` in testing, so the pairing exchange is
> exercised only shallowly.

## 10. Navigation

Phone → HU, for instrument-cluster turn-by-turn. The HU does not normally send
nav messages to the phone.

> **Unverified ids.** The aasdk protos in this repo contain **no** navigation
> *channel message-id* enum — only `NAVIGATION_FOCUS_REQUEST/RESPONSE` on the
> control channel. Our `NavigationChannel` uses ids `0x8003` (status),
> `0x8004` (turn event), `0x8005` (distance event), which appear to have been
> chosen by hand and are **not confirmed against the protos or observed
> traffic**. Treat as unverified; the feature is untested on hardware.
>
> Message *shapes* do exist in the protos: `NavigationChannelData.proto`,
> `NavigationImageOptionsData.proto`, `NavigationFocusRequestMessage.proto`,
> `NavigationFocusResponseMessage.proto`.

---

## 11. Known ambiguities and traps (summary)

1. **Sensor responses are uncorrelatable except by order** (§5.2). A dropped
   response silently shifts all later verdicts. Always check
   `correlation=OK|SUSPECT`.
2. **`AUTH_COMPLETE` must never be sent to the HU** (§2).
3. **HU→phone `AUDIO_FOCUS_REQUEST` is a notification** — do not reply (§4).
4. **Failure status is `247`, not the proto's `FAIL=1`** (§5.3).
5. **Types 18 and 21 have no payload field** — they can never deliver (§5.4).
6. **`DEAD_RECONING` (14) is actually `SteeringWheel`** (§5.6).
7. **Video config index is ours to choose and send in SETUP** (§6.2) — the HUIG
   prose contradicts the proto; trust the proto.
8. **Video focus must be released** or the HU's own UI corrupts (§6.3).
9. **Channel ids must be parsed, not hardcoded** (§3).
10. **Fragmentation is off by default**, so that path is under-tested (§1).

## 12. How these facts were established

- Protos: `third_party/aasdk/aasdk_proto/` (read the `message` bodies, not the
  GPL headers — the headers dominate the files).
- HUIG: the milek7 mirror, fetched and text-searched. Written **head-unit-side**;
  it describes what to send, not how to interpret what comes back.
- Observed traffic: `aa_log_*.txt` from the real HU, with the logging described
  in `connection-stability.md` §6.

**Corrections welcome.** Several statements here have already been wrong once
(see `connection-stability.md` §5 for the sensor capability saga). Where a claim
rests on a single observation it is marked as such.
