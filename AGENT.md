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
- `f1xpl/aasdk` (C++, GPL-3.0) — original protocol library.

## Development Guidelines
- Always verify working directory and use `python3` if running Python scripts.
- Use Gradle for building Android Kotlin projects.
- Keep documentation, steering files, and architecture notes up-to-date.
