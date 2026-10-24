# AndroidAuto Project Agent Guidelines

## Overview
This repository contains an open-source implementation of the Android Auto **phone-side** application—the app running on an Android device that projects to a car's head unit over USB or WiFi (replacing Google's proprietary `com.google.android.projection.gearhead` APK).

## Tech Stack
- **Language**: Kotlin
- **Platform**: Android
- **Protocol**: Android Auto Protocol (AAP) — TLS + Protobuf over USB AOA / WiFi

## Key References & Ecosystem
- `uglyoldbob/android-auto` (Rust, LGPL-3.0) — clean protocol implementation with protobuf definitions.
- `andreknieriem/headunit-revived` (Kotlin, AGPL-3.0) — most complete head-unit side implementation.
- `f1xpl/aasdk` (C++, GPL-3.0) — original protocol library (vendored at `third_party/aasdk`).

## Documentation map

| Doc | Contents |
|---|---|
| `docs/protocol.md` | AAP reference: framing, TLS, channels, message catalogue, sensor model, video/focus/flow control, **known ambiguities and traps**. |
| `docs/connection-stability.md` | Connection reliability: HU-enforced requirements, the two disconnect signatures, evidence log, ruled-out causes, reconnect behaviour, instrumentation/log reference, sensors, external research, TODO. |
| `docs/research.md` | Ecosystem survey: history, other OSS projects, TLS/auth analysis, APK notes. |
| `README.md` | Feature checklist, build/test instructions, known bugs. |

## Development Guidelines
- Always verify working directory and use `python3` if running Python scripts.
- Use Gradle for building Android Kotlin projects.
- Unit tests: `./gradlew :app:testDebugUnitTest`. Keep them green.

## Keeping documentation up to date — REQUIRED

Docs are a deliverable in this repo, not an afterthought. Update them **in the
same change** as the code, not "later".

1. **Protocol facts → `docs/protocol.md`.** Any newly-verified framing rule,
   message id, field number, unit, enum value, or direction of travel goes here.
   If you discover that something documented here is **wrong**, fix the doc and
   say so — do not silently leave a stale claim.
2. **Stability findings → `docs/connection-stability.md`.** Add to the evidence
   log with a date and the session timestamp. Record ruled-out causes, and the
   TODO. This file is the project's memory of what has been tried.
3. **Feature status → `README.md`.** Tick/untick checklists, and correct any
   "known bug" entry that has been fixed or re-opened.
4. **Summarise real test runs** with the log filename and the computed duration,
   so claims are traceable to evidence.

### Evidence discipline — important lessons learned

This project has repeatedly been misled by confident-but-unverified claims, from
both humans and agents. Before writing that something is true:

- **Distinguish "observed" from "assumed".** Mark single observations as such.
- **Check correlations before trusting them.** Sensor responses are matched to
  requests *by order only* (`docs/protocol.md` §5.2); one dropped response
  silently shifts every verdict. Treat `correlation=SUSPECT` runs as invalid.
- **Distinguish a head-unit drop from a cable pull.** Measure the gap between the
  last inbound frame and the EIO — see `docs/connection-stability.md` §2.2.
- **Count before concluding.** "27 requests / 27 responses" is evidence;
  "looks right" is not.
- **Do not extrapolate from an unasked question.** Concluding "the HU sends no
  CAN data" while subscribing to only 3 of 21 sensor types was wrong, and cost
  several days.
- When you correct an earlier claim, **say plainly that it was wrong and why** —
  in the commit message and the doc.
