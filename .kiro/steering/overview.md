# AndroidAuto

An open-source Android Auto phone-side application.

## Goal

Create a fully open-source implementation of the Android Auto **phone-side** app — the app that runs on your phone and projects to a car's head unit over USB or WiFi. This replaces Google's proprietary `com.google.android.projection.gearhead` APK.

## Tech Stack

- **Language**: Kotlin
- **Platform**: Android
- **Protocol**: Android Auto Protocol (AAP) — TLS + Protobuf over USB AOA / WiFi

## Key References

- `uglyoldbob/android-auto` (Rust, LGPL-3.0) — cleanest protocol implementation with protobuf defs
- `andreknieriem/headunit-revived` (Kotlin, AGPL-3.0) — most complete head-unit side implementation
- `f1xpl/aasdk` (C++, GPL-3.0) — original protocol library

## Project Location

`/home/mark/git/AndroidAuto`
