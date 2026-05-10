# Open Android Auto

An open-source implementation of the Android Auto **phone-side** app. This app runs on your phone and projects to a car's head unit over USB, replacing Google's proprietary `com.google.android.projection.gearhead` APK.

## ⚠️ WORK IN PROGRESS

This project is in early development. It is **not yet functional** with real head units. The protocol handshake is partially working but video projection, audio, and input are not yet operational in a real car environment.

## ⚠️ DISCLAIMER

**USE AT YOUR OWN RISK.** This software is provided "as is", without warranty of any kind.

- This software may cause unexpected behaviour with your car's head unit
- This software may damage your phone or head unit — the authors accept no liability
- **DO NOT use this application while driving**
- **DO NOT interact with this application while operating a vehicle**
- This application is intended for development and testing purposes only
- Always pull over and stop your vehicle before interacting with any phone application
- The authors are not responsible for any accidents, injuries, or damages resulting from the use of this software

## Architecture

```
USB Plug-in → MainActivity → ProjectionService
                                    ↓
                    UsbAoaTransport (USB AOA accessory mode)
                                    ↓
                    MessageFramer (16KB frame fragmentation)
                                    ↓
                    InBandTls (TLSv1.2 via SSLEngine)
                                    ↓
                    ProtocolEngine (AAP state machine)
                                    ↓
                    ┌───────────┼───────────┐
                 Video      Input       Audio
                (H.264)   (touch/keys)  (PCM)
```

## Building

```bash
./gradlew assembleDebug
```

Requires Android SDK with platform 35.

## Testing

```bash
./gradlew testDebugUnitTest
```

72 unit and integration tests covering protocol, framing, TLS, and channel logic.

## Protocol References

- [uglyoldbob/android-auto](https://github.com/uglyoldbob/android-auto) (Rust, LGPL-3.0) — protocol implementation with protobuf definitions
- [headunit-revived](https://github.com/andreknieriem/headunit-revived) (Kotlin, AGPL-3.0) — head-unit side implementation
- [f1xpl/aasdk](https://github.com/nickel-org/nickel.rs) (C++, GPL-3.0) — original protocol library

## License

TBD
