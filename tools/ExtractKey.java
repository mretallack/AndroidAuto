/*
 * ExtractKey.java — Desktop-only Android Auto CarService private key extractor.
 *
 * Extracts the RSA private key from the Android Auto APK without needing
 * an Android device.  The key obstacle is that the APK's decryption code
 * uses android.util.Base64.decode(data, 2) which silently skips non-base64
 * bytes (the "SKIP" behavior).  Desktop JVM decoders reject those bytes.
 *
 * This tool ports the exact AOSP Base64 SKIP-filtering decoder (Apache 2.0)
 * so the output is bit-identical to an on-device extraction.
 *
 * Usage:
 *   javac ExtractKey.java
 *   java ExtractKey <salt.bin> <enckey.bin> <carservice_cert.pem> [gal_ca.pem]
 *
 * Inputs (raw binary files):
 *   salt.bin           — 256-byte KDF salt (sbd.b / ivq.b)
 *   enckey.bin         — ~1712-byte AES-CBC ciphertext (sbd.c / ivq.c)
 *   carservice_cert.pem — CarService certificate PEM (sba.a() / jen.a())
 *   gal_ca.pem         — Google Automotive Link CA PEM (optional; uses
 *                         the well-known cert if omitted)
 *
 * Output: PKCS#8 PEM private key to stdout.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Base64 decoder tables and state machine ported from AOSP
 * android/util/Base64.java (Apache-2.0, Copyright 2010 The Android Open
 * Source Project).
 */

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class ExtractKey {

    // ---------------------------------------------------------------
    //  AOSP-compatible Base64 decoder (SKIP-filtering)
    //
    //  Ported from:
    //    https://android.googlesource.com/platform/frameworks/base/
    //    +/refs/heads/main/core/java/android/util/Base64.java
    //
    //  The critical property: bytes that don't map to a base64 value
    //  are SKIP (-1) and silently ignored, rather than causing an error.
    //  This matches android.util.Base64.decode(data, 2) exactly.
    // ---------------------------------------------------------------

    /** Standard Base64 decode table.  -1 = SKIP, -2 = EQUALS ('='). */
    private static final int[] DECODE = {
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 62, -1, -1, -1, 63,
        52, 53, 54, 55, 56, 57, 58, 59, 60, 61, -1, -1, -1, -2, -1, -1,
        -1,  0,  1,  2,  3,  4,  5,  6,  7,  8,  9, 10, 11, 12, 13, 14,
        15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, -1, -1, -1, -1, -1,
        -1, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40,
        41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51, -1, -1, -1, -1, -1,
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
    };
    private static final int SKIP   = -1;
    private static final int EQUALS = -2;

    /**
     * Decode base64 with AOSP's SKIP-filtering behavior.
     * Non-base64 bytes are silently ignored (not rejected).
     * This is the exact behavior of android.util.Base64.decode(data, 0|2).
     */
    static byte[] aospBase64Decode(byte[] input) {
        byte[] output = new byte[input.length * 3 / 4 + 10];
        int op = 0;
        int state = 0;
        int value = 0;

        for (int p = 0; p < input.length; p++) {
            int d = DECODE[input[p] & 0xff];

            switch (state) {
                case 0:
                    if (d >= 0) { value = d; state = 1; }
                    else if (d != SKIP) return null; // EQUALS in state 0 = error
                    break;
                case 1:
                    if (d >= 0) { value = (value << 6) | d; state = 2; }
                    else if (d != SKIP) return null;
                    break;
                case 2:
                    if (d >= 0) { value = (value << 6) | d; state = 3; }
                    else if (d == EQUALS) { output[op++] = (byte)(value >> 4); state = 4; }
                    else if (d != SKIP) return null;
                    break;
                case 3:
                    if (d >= 0) {
                        value = (value << 6) | d;
                        output[op+2] = (byte) value;
                        output[op+1] = (byte)(value >> 8);
                        output[op]   = (byte)(value >> 16);
                        op += 3;
                        state = 0;
                    } else if (d == EQUALS) {
                        output[op+1] = (byte)(value >> 2);
                        output[op]   = (byte)(value >> 10);
                        op += 2;
                        state = 5;
                    } else if (d != SKIP) return null;
                    break;
                case 4:
                    if (d == EQUALS) state = 5;
                    else if (d != SKIP) return null;
                    break;
                case 5:
                    if (d != SKIP) return null;
                    break;
            }
        }

        // Finalize: emit any remaining partial tuple
        switch (state) {
            case 0: break; // clean end
            case 1: return null; // dangling single byte
            case 2: output[op++] = (byte)(value >> 4); break;
            case 3: output[op++] = (byte)(value >> 10);
                    output[op++] = (byte)(value >> 2); break;
            case 4: return null; // single '=' when 2 expected
            case 5: break; // normal end after padding
        }

        byte[] result = new byte[op];
        System.arraycopy(output, 0, result, 0, op);
        return result;
    }

    // ---------------------------------------------------------------
    //  KDF: tweakBytes (uga.bl)
    //  Verified against baksmali bytecode of uga.bl in AA v17.7
    // ---------------------------------------------------------------

    static void tweakBytes(byte[] src, byte[] buf, byte[] salt) {
        for (int i = 0; i < src.length; i++) {
            for (int j = 0; j < 48; j++) {
                int v = buf[j] & 0xff;   // MUST be int, not byte
                buf[j] = (byte)(((((v >> 7) | (v + v)) + 33) ^ salt[j % salt.length]) ^ src[i]);
            }
        }
    }

    // ---------------------------------------------------------------
    //  The well-known Google Automotive Link CA certificate PEM
    // ---------------------------------------------------------------

    static final String GAL_CA_PEM =
        "-----BEGIN CERTIFICATE-----\n" +
        "MIIDiTCCAnGgAwIBAgIJAMFO56WkVE1CMA0GCSqGSIb3DQEBBQUAMFsxCzAJBgNV\n" +
        "BAYTAlVTMRMwEQYDVQQIDApDYWxpZm9ybmlhMRYwFAYDVQQHDA1Nb3VudGFpbiBW\n" +
        "aWV3MR8wHQYDVQQKDBZHb29nbGUgQXV0b21vdGl2ZSBMaW5rMB4XDTE0MDYwNjE4\n" +
        "MjgxOVoXDTQ0MDYwNTE4MjgxOVowWzELMAkGA1UEBhMCVVMxEzARBgNVBAgMCkNh\n" +
        "bGlmb3JuaWExFjAUBgNVBAcMDU1vdW50YWluIFZpZXcxHzAdBgNVBAoMFkdvb2ds\n" +
        "ZSBBdXRvbW90aXZlIExpbmswggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAwggEKAoIB\n" +
        "AQDUH+iIbwwVb74NdI5eBv/ACFmh4ml/NOW7gUVWdYX50n8uQQsHHLCNIhk5VV2H\n" +
        "hanvAZ/XXHPuVAPadE2HpnNqePKF/RDo4eJo/+rOief8gBYq/Z+OQTZeLdNm+GoI\n" +
        "HBrEjU4Ms8IdLuFW0jF8LlIRgekjLHpc7duUl3QpwBlmAWQK40T/SZjprlmhyqfJ\n" +
        "g1rxFdnGbrSibmCsTmb3m6WZyZUyrcwmd7t6q3pHbMABO+o02asPG/YPj/SJo4+i\n" +
        "fb5/Nk56f3hH9pBiPKQXJnVUdVLKMXSRgydDBsGSBol4C0JL77MNDrMR5jdafJ4j\n" +
        "mWmsa2+mnzoAv9AxEL9T0LiNAgMBAAGjUDBOMB0GA1UdDgQWBBS5dqvv8DPQiwrM\n" +
        "fgn8xKR91k7wgjAfBgNVHSMEGDAWgBS5dqvv8DPQiwrMfgn8xKR91k7wgjAMBgNV\n" +
        "HRMEBTADAQH/MA0GCSqGSIb3DQEBBQUAA4IBAQDKcnBsrbB0Jbz2VGJKP2lwYB6P\n" +
        "dCTCCpQu7dVp61UQOX+zWfd2hnNMnLs/r1xPO+eyN0vmw7sD05phaIhbXVauKWZi\n" +
        "9WqWHTaR+9s6CTyBOc1Mye0DMj+4vHt+WLmf0lYjkYUVYvR1EImX8ktXzkVmOqn+\n" +
        "e30siqlZ8pQpsOgegIKfJ+pNQM8c3eXVv3KFMUgjZW33SziZL8IMsLvSO+1LtH37\n" +
        "KqbTEMP6XUwVuZopgGvaHU74eT/WSRGlL7vX4OL5/UXXP4qsGH2Zp7uQlErv4H9j\n" +
        "kMs37UL1vGb4M8RM7Eyu9/RulepSmqZUF+3i+3eby8iGq/3OWk9wgJf7AXnx\n" +
        "-----END CERTIFICATE-----\n";

    // ---------------------------------------------------------------
    //  Main
    // ---------------------------------------------------------------

    static byte[] readFile(String path) throws Exception {
        File f = new File(path);
        byte[] data = new byte[(int) f.length()];
        try (FileInputStream fis = new FileInputStream(f)) {
            int n = fis.read(data);
            if (n != data.length) throw new IOException("Short read: " + path);
        }
        return data;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: java ExtractKey <salt.bin> <enckey.bin> <carservice_cert.pem> [gal_ca.pem]");
            System.err.println();
            System.err.println("Extracts the CarService RSA private key from Android Auto APK data.");
            System.err.println("Runs entirely on desktop JVM — no Android device needed.");
            System.exit(1);
        }

        byte[] salt    = readFile(args[0]);
        byte[] encKey  = readFile(args[1]);
        byte[] certPem = readFile(args[2]);
        byte[] galPem  = args.length > 3 ? readFile(args[3])
                                         : GAL_CA_PEM.getBytes(StandardCharsets.UTF_8);

        if (salt.length != 256) {
            System.err.println("WARNING: salt is " + salt.length + " bytes (expected 256)");
        }
        if (encKey.length % 16 != 0) {
            System.err.println("ERROR: encrypted key is " + encKey.length + " bytes (not a multiple of 16)");
            System.exit(1);
        }

        // --- KDF: derive 48-byte buffer → AES-256 key (0..32) + IV (32..48) ---
        byte[] buf = new byte[48];
        tweakBytes(certPem, buf, salt);   // seed 1: CarService cert
        tweakBytes(galPem,  buf, salt);   // seed 2: GAL CA cert
        for (int i = 0; i < 7; i++) {     // 7 self-mixing rounds
            tweakBytes(buf, buf, salt);
        }

        byte[] aesKey = new byte[32];
        System.arraycopy(buf, 0, aesKey, 0, 32);
        IvParameterSpec iv = new IvParameterSpec(buf, 32, 16);

        // --- AES-256-CBC decrypt ---
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"), iv);
        byte[] decrypted;
        try {
            decrypted = cipher.doFinal(encKey);
        } catch (Exception e) {
            System.err.println("ERROR: AES decryption failed — wrong salt/cert/key combination?");
            System.err.println("  " + e.getMessage());
            System.exit(1);
            return;
        }

        // --- Extract PKCS#8 DER: skip 28 front, trim 26 back, AOSP Base64 decode ---
        if (decrypted.length < 55) {
            System.err.println("ERROR: decrypted data too short (" + decrypted.length + " bytes)");
            System.exit(1);
        }
        int midLen = decrypted.length - 54;
        byte[] mid = new byte[midLen];
        System.arraycopy(decrypted, 28, mid, 0, midLen);

        byte[] der = aospBase64Decode(mid);
        if (der == null || der.length == 0) {
            System.err.println("ERROR: AOSP Base64 decode failed on decrypted payload");
            System.exit(1);
        }

        // --- Validate as RSA private key ---
        RSAPrivateKey pk;
        try {
            pk = (RSAPrivateKey) KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            System.err.println("ERROR: decrypted data is not a valid PKCS#8 RSA key");
            System.err.println("  " + e.getMessage());
            System.exit(1);
            return;
        }

        // --- Output PEM ---
        String b64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der);
        System.out.println("-----BEGIN PRIVATE KEY-----");
        System.out.println(b64);
        System.out.println("-----END PRIVATE KEY-----");

        // --- Summary to stderr ---
        System.err.println("SUCCESS: extracted " + pk.getModulus().bitLength() + "-bit RSA key");
        System.err.println("  DER length: " + der.length);
        System.err.println("  Modulus (first 32 hex): " +
            pk.getModulus().toString(16).substring(0, 32).toUpperCase() + "...");
    }
}
