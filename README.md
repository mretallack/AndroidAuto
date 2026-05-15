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
- [f1xpl/aasdk](https://github.com/f1xpl/aasdk) (C++, GPL-3.0) — original protocol library

## TLS Authentication

Android Auto uses mutual TLS. The phone acts as the TLS **server** and must present a certificate signed by the **Google Automotive Link CA** (baked into head unit firmware). Without the correct private key, the head unit rejects the connection with AUTH_COMPLETE status=-3.

### Certificate Chain

The phone presents a 2-cert chain:
1. **CarService cert** — `O=CarService`, signed by the Google Automotive Link CA
2. **Google Automotive Link CA** — self-signed root, `O=Google Automotive Link` (valid 2014-2044)

### Obtaining the Private Key

The private key is AES-256-CBC encrypted inside the Android Auto APK. The head unit validates the phone's cert against the Google Automotive Link CA — any cert signed by that CA is accepted, **regardless of expiry date** (head units don't check expiry).

#### Path A: Decrypt from the APK (offline)

The key can be extracted by running the APK's own decryption code. The process:

1. Decompile the AA APK with JADX
2. Find the class implementing the cert provider interface (has a cert PEM string + two `byte[]` arrays)
3. Run the decryption function (`AES/CBC/PKCS5Padding`) with the custom KDF

**Important:** The APK must be pulled from a real phone (`adb pull`), not downloaded from APKPure/APKMirror. Download sites may cache old builds with stale byte arrays that won't decrypt.

**Critical JADX bug:** JADX decompiles the KDF helper as `byte b = bArr2[i2] & 255;` but it must be `int b = bArr2[i2] & 255;`. The `byte` type truncates back to signed, producing garbage output. Fix this to `int` and the decryption works.

The KDF (`tweakBytes`/`ap`) function:
```java
static void tweakBytes(byte[] bArr, byte[] bArr2, byte[] bArr3) {
    for (int i = 0; i < bArr.length; i++) {
        for (int i2 = 0; i2 < 48; i2++) {
            int b = bArr2[i2] & 255;  // MUST be int, not byte
            bArr2[i2] = (byte) (((((b >> 7) | (b + b)) + 33) ^ bArr3[i2 % bArr3.length]) ^ bArr[i]);
        }
    }
}
```

After AES decryption, the `T()` function extracts the key:
- Skip first 28 bytes, trim last 26 bytes
- Base64 decode (URL_SAFE, flag=2) the middle portion
- Result is PKCS#8 DER encoded RSA private key

**Note:** Must run on Android (not desktop JVM) due to `android.util.Base64` vs `java.util.Base64` differences. The desktop JVM's `Base64.getUrlDecoder()` rejects standard base64 characters (`+`, `/`) and newlines that Android's decoder accepts. Use `Base64.getMimeDecoder()` on desktop, or run the decryption on-device with `dalvikvm`:

```bash
# Compile to DEX and run on any Android device with ADB access
javac Decrypt.java -d out
d8 out/Decrypt.class --output dex_out
adb push dex_out/classes.dex /data/local/tmp/decrypt.dex
adb shell "dalvikvm -cp /data/local/tmp/decrypt.dex Decrypt"
```

This outputs the PKCS#8 private key in base64. Wrap it in PEM headers and place at `app/src/main/assets/carservice_key.pem`.

See `tools/decrypt_key_from_apk.md` for detailed steps.

#### Path B: Use a previously extracted cert+key

Since head units don't check certificate expiry, any previously extracted cert+key pair (even expired) will work. Sources:

1. **Contact opengal_proxy author** — email `som@marekkraus.sk` (see [gamelaster/opengal_proxy](https://github.com/gamelaster/opengal_proxy))
2. **Extract from a rooted phone with GApps** — use Frida to hook `KeyFactory.generatePrivate()`:
   ```bash
   frida -U -n "com.google.android.projection.gearhead" -l tools/dump_key_frida.js
   ```
3. **Community** — see [AACS#15](https://github.com/tomasz-grobelny/AACS/issues/15) for discussion

Once obtained, place the cert+key at `app/src/main/assets/carservice_key.pem`.

See `tools/dump_key.sh` and `tools/dump_key_frida.js` for runtime extraction scripts.

## License

This project is licensed under the [GNU General Public License v3.0](LICENSE).

This project includes protocol buffer definitions from [aasdk](https://github.com/f1xpl/aasdk) (GPLv3, Copyright © 2018 f1x.studio / Michal Szwaj) as a git submodule.
