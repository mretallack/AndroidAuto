# Extracting the Private Key from the Android Auto APK

## Overview

The Android Auto APK (`com.google.android.projection.gearhead`) contains an AES-256-CBC encrypted RSA private key. This key is used for TLS authentication with head units. This document describes how to extract it.

## Prerequisites

- An Android phone with **Google Play Services** and the **Android Auto app** installed
- ADB access to the phone
- Android SDK (specifically `d8` from build-tools and `adb`)
- JADX for decompilation (optional, for finding byte arrays in new APK versions)

## Important Notes

- The APK must come from a **real phone** (not a download site like APKPure — they may cache old builds with different byte arrays)
- The decryption **must run on Android** (`dalvikvm`), not on a desktop JVM. Android's `Base64.decode(data, 2)` is lenient (accepts `+`, `/`, ignores whitespace). Desktop Java's decoders are stricter.
- JADX has a critical decompilation bug: it outputs `byte b = bArr2[i2] & 255;` which must be `int b = bArr2[i2] & 255;`
- The embedded cert expires ~8 months after APK release, but head units don't check expiry

## Step-by-Step Extraction

### Step 1: Pull the APK from the phone

```bash
adb shell pm path com.google.android.projection.gearhead
# Look for the base.apk path, then:
adb pull /data/app/.../base.apk /tmp/aa.apk
```

### Step 2: Extract the DEX file

```bash
mkdir /tmp/aa_dex
unzip /tmp/aa.apk classes.dex -d /tmp/aa_dex
```

### Step 3: Find the byte arrays

Decompile with JADX and search for the class implementing the cert provider interface. In recent versions, look for:
- A class with a method returning a cert PEM string (contains `"-----BEGIN CERTIFICATE-----"`)
- The same class (or a related one) with two `static final byte[]` fields:
  - **Salt** — 256 bytes (used as KDF parameter)
  - **Encrypted key** — ~1712 bytes (the AES-CBC ciphertext)

In v16.8, these are in class `ivq` (fields `b` and `c`). In v6.4, they're in `SslWrapper` (fields `o` and `p`). The obfuscated names change between versions.

Alternatively, extract them directly from the DEX binary:
```python
# The cert PEM is a searchable string in the DEX
with open('classes.dex', 'rb') as f:
    dex = f.read()
idx = dex.find(b"-----BEGIN CERTIFICATE-----")
# The 256-byte salt is typically ~5KB before the cert in the data section
```

### Step 4: Save the binary data to files

Save these as raw binary files:
- `ivq_b.bin` — the 256-byte salt array
- `ivq_c.bin` — the ~1712-byte encrypted key array
- `cert1.bin` — the CarService cert PEM (as UTF-8 bytes, including `-----BEGIN/END-----` and newlines)
- `cert2.bin` — the Google Automotive Link CA cert PEM

The CA cert is always the same across all versions:
```
-----BEGIN CERTIFICATE-----
MIIDiTCCAnGgAwIBAgIJAMFO56WkVE1CMA0GCSqGSIb3DQEBBQUAMFsxCzAJBgNV
BAYTAlVTMRMwEQYDVQQIDApDYWxpZm9ybmlhMRYwFAYDVQQHDA1Nb3VudGFpbiBW
aWV3MR8wHQYDVQQKDBZHb29nbGUgQXV0b21vdGl2ZSBMaW5rMB4XDTE0MDYwNjE4
MjgxOVoXDTQ0MDYwNTE4MjgxOVowWzELMAkGA1UEBhMCVVMxEzARBgNVBAgMCkNh
bGlmb3JuaWExFjAUBgNVBAcMDU1vdW50YWluIFZpZXcxHzAdBgNVBAoMFkdvb2ds
ZSBBdXRvbW90aXZlIExpbmswggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAwggEKAoIB
AQDUH+iIbwwVb74NdI5eBv/ACFmh4ml/NOW7gUVWdYX50n8uQQsHHLCNIhk5VV2H
hanvAZ/XXHPuVAPadE2HpnNqePKF/RDo4eJo/+rOief8gBYq/Z+OQTZeLdNm+GoI
HBrEjU4Ms8IdLuFW0jF8LlIRgekjLHpc7duUl3QpwBlmAWQK40T/SZjprlmhyqfJ
g1rxFdnGbrSibmCsTmb3m6WZyZUyrcwmd7t6q3pHbMABO+o02asPG/YPj/SJo4+i
fb5/Nk56f3hH9pBiPKQXJnVUdVLKMXSRgydDBsGSBol4C0JL77MNDrMR5jdafJ4j
mWmsa2+mnzoAv9AxEL9T0LiNAgMBAAGjUDBOMB0GA1UdDgQWBBS5dqvv8DPQiwrM
fgn8xKR91k7wgjAfBgNVHSMEGDAWgBS5dqvv8DPQiwrMfgn8xKR91k7wgjAMBgNV
HRMEBTADAQH/MA0GCSqGSIb3DQEBBQUAA4IBAQDKcnBsrbB0Jbz2VGJKP2lwYB6P
dCTCCpQu7dVp61UQOX+zWfd2hnNMnLs/r1xPO+eyN0vmw7sD05phaIhbXVauKWZi
9WqWHTaR+9s6CTyBOc1Mye0DMj+4vHt+WLmf0lYjkYUVYvR1EImX8ktXzkVmOqn+
e30siqlZ8pQpsOgegIKfJ+pNQM8c3eXVv3KFMUgjZW33SziZL8IMsLvSO+1LtH37
KqbTEMP6XUwVuZopgGvaHU74eT/WSRGlL7vX4OL5/UXXP4qsGH2Zp7uQlErv4H9j
kMs37UL1vGb4M8RM7Eyu9/RulepSmqZUF+3i+3eby8iGq/3OWk9wgJf7AXnx
-----END CERTIFICATE-----
```

### Step 5: Push data to the phone

```bash
adb push ivq_b.bin /data/local/tmp/ivq_b.bin
adb push ivq_c.bin /data/local/tmp/ivq_c.bin
adb push cert1.bin /data/local/tmp/cert1.bin
adb push cert2.bin /data/local/tmp/cert2.bin
```

### Step 6: Create the decryption Java class

Save as `Decrypt.java`:

```java
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.util.Base64;

public class Decrypt {
    public static void main(String[] args) throws Exception {
        byte[] salt = readFile("/data/local/tmp/ivq_b.bin");
        byte[] encrypted = readFile("/data/local/tmp/ivq_c.bin");
        byte[] certBytes = readFile("/data/local/tmp/cert1.bin");
        byte[] caBytes = readFile("/data/local/tmp/cert2.bin");

        byte[] bArr = new byte[48];
        tweakBytes(certBytes, bArr, salt);
        tweakBytes(caBytes, bArr, salt);
        for (int i = 0; i < 7; i++) tweakBytes(bArr, bArr, salt);

        byte[] aesKey = new byte[32];
        System.arraycopy(bArr, 0, aesKey, 0, 32);

        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(2, new SecretKeySpec(aesKey, "AES"), new IvParameterSpec(bArr, 32, 16));
        byte[] doFinal = cipher.doFinal(encrypted);

        int length = doFinal.length - 54;
        byte[] bArr3 = new byte[length];
        System.arraycopy(doFinal, 28, bArr3, 0, length);

        // Use MIME decoder (handles newlines in the base64 data)
        byte[] keyDer = Base64.getMimeDecoder().decode(bArr3);
        System.out.println("SUCCESS:DER_LENGTH=" + keyDer.length);
        System.out.println("KEY:" + Base64.getEncoder().encodeToString(keyDer));
    }

    static void tweakBytes(byte[] bArr, byte[] bArr2, byte[] bArr3) {
        for (int i = 0; i < bArr.length; i++) {
            for (int i2 = 0; i2 < 48; i2++) {
                int b = bArr2[i2] & 255;  // MUST be int, not byte!
                bArr2[i2] = (byte) (((((b >> 7) | (b + b)) + 33) ^ bArr3[i2 % bArr3.length]) ^ bArr[i]);
            }
        }
    }

    static byte[] readFile(String path) throws Exception {
        File f = new File(path);
        byte[] data = new byte[(int) f.length()];
        new FileInputStream(f).read(data);
        return data;
    }
}
```

### Step 7: Compile to DEX

```bash
# Compile Java to .class
javac Decrypt.java -d decrypt_cls

# Convert to DEX using d8 (from Android SDK build-tools)
mkdir decrypt_dex
d8 decrypt_cls/Decrypt.class --output decrypt_dex
```

### Step 8: Push DEX and run on device

```bash
adb push decrypt_dex/classes.dex /data/local/tmp/decrypt.dex
adb shell "dalvikvm -cp /data/local/tmp/decrypt.dex Decrypt"
```

Expected output:
```
SUCCESS:DER_LENGTH=1217
KEY:MIIEvQIBADANBgkqhkiG9w0BAQEFAASC...
```

### Step 9: Convert to PEM

Take the base64 output from `KEY:` and wrap it:

```bash
echo "-----BEGIN PRIVATE KEY-----" > carservice_key.pem
echo "<base64 from KEY: output>" >> carservice_key.pem
echo "-----END PRIVATE KEY-----" >> carservice_key.pem
```

Validate:
```bash
openssl pkey -in carservice_key.pem -noout -check
# Should output: Key is valid
```

### Step 10: Verify key matches cert

```bash
# Extract modulus from cert
openssl x509 -in carservice_cert.pem -noout -modulus | md5sum
# Extract modulus from key
openssl pkey -in carservice_key.pem -noout -modulus | md5sum
# Both should match
```

## Why Desktop JVM Fails

Android's `android.util.Base64.decode(data, 2)` (flag 2 = URL_SAFE):
- Accepts both standard (`+`, `/`) and URL-safe (`-`, `_`) characters
- Ignores whitespace (newlines, spaces)
- Handles missing padding

Java's `java.util.Base64.getUrlDecoder()`:
- Rejects standard characters (`+`, `/`) — throws `IllegalArgumentException`
- Rejects whitespace — throws `IllegalArgumentException`

Java's `java.util.Base64.getMimeDecoder()`:
- Accepts standard characters ✓
- Ignores whitespace ✓
- This is the correct desktop equivalent

## Troubleshooting

- **"Illegal base64 character 2f"** — You're using `getUrlDecoder()`. Use `getMimeDecoder()` instead.
- **"BadPaddingException"** — Wrong AES key. Check that:
  - The `int b = ... & 255` fix is applied (not `byte b`)
  - The cert PEM includes the trailing newline
  - The byte arrays match the APK version on the phone
- **Garbage output** — APK from download site may differ from phone. Always pull from a real device.

## References

- [AACS#15](https://github.com/tomasz-grobelny/AACS/issues/15) — Original discussion
- [opengal_proxy#2](https://github.com/gamelaster/opengal_proxy/issues/2) — Certificate extraction discussion
- Credit: @thegnomewizard for discovering the JADX bug and confirming the extraction method
