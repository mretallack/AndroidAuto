package org.openandroidauto.sensor

/**
 * Real sensor payloads captured from a Dacia MediaNav head unit
 * (session `aa_log_2026-10-26_08-54-38.txt`).
 *
 * Each entry is the **decrypted plaintext** of one `SENSOR_EVENT_INDICATION`
 * (0x8003) message, i.e. starting at the 2-byte message id `80 03`. These are
 * taken verbatim from the session log's `decrypt: … post=[…]` lines, so they are
 * ground truth for what the head unit actually sends.
 *
 * Used to test the whole decode chain: message id → outer field tag → nested
 * message body → scaled engineering value.
 */
object CapturedSensorVectors {

    /** `80 03 1a 03 08 80 25` → CAR_SPEED, f1 = 0x1280 = 4736 → 4.736 m/s. */
    val CAR_SPEED_4736 = hex("80 03 1a 03 08 80 25")

    /** `80 03 1a 03 08 80 48` → CAR_SPEED, f1 = 0x2400 = 9216 → 9.216 m/s. */
    val CAR_SPEED_9216 = hex("80 03 1a 03 08 80 48")

    /** `80 03 1a 02 08 00` → CAR_SPEED, f1 = 0 → 0 m/s (stationary). */
    val CAR_SPEED_ZERO = hex("80 03 1a 02 08 00")

    /** `80 03 6a 02 08 00` → DRIVING_STATUS, f1 = 0 (UNRESTRICTED). */
    val DRIVING_STATUS_UNRESTRICTED = hex("80 03 6a 02 08 00")

    /** `80 03 6a 02 08 09` → DRIVING_STATUS, f1 = 9 (NO_VIDEO|NO_CONFIG). */
    val DRIVING_STATUS_NO_VIDEO_NO_CONFIG = hex("80 03 6a 02 08 09")

    /** `80 03 52 02 08 01` → NIGHT_DATA, f1 = 1 (night). */
    val NIGHT_MODE_ON = hex("80 03 52 02 08 01")

    /** `80 03 3a 02 08 01` → PARKING_BRAKE, f1 = 1 (engaged). */
    val PARKING_BRAKE_ON = hex("80 03 3a 02 08 01")

    /** `80 03 3a 02 08 00` → PARKING_BRAKE, f1 = 0 (released). */
    val PARKING_BRAKE_OFF = hex("80 03 3a 02 08 00")

    /**
     * A full LOCATION message as it appears on the wire.
     *
     * Structure (this is the important bit):
     *   `80 03`        message id (stripped by routeMessage)
     *   `0a 21`        outer tag: field 1 (LOCATION) wiretype 2, length 33
     *   `08 00 …`      nested GPSLocation body
     *
     * The nested body is what the channel's `handleSensorBatch` walks:
     *   f1(timestamp)=0, f2(lat)=523387498, f3(lon)=-34823916,
     *   f4(accuracy)=1054, f5(altitude)=34145, f6(speed)=8, f7(bearing)=189513946
     *
     * NOTE: the session log's `RAW LOCATION` line prints the *inner* bytes (the
     * trace runs after the outer tag was consumed), which is why the raw trace
     * and this wire form differ by the leading `0a 21`.
     */
    val LOCATION_FULL = hex(
        "80 03 0a 21 08 00 10 ea 84 c9 f9 01 18 94 c2 b2 ef ff ff ff ff ff 01 " +
        "20 9e 08 28 e1 8a 02 30 08 38 da 81 af 5a"
    )

    /**
     * REAL ACCEL messages, verbatim decrypted payloads from the session log.
     *
     * These are the payloads that exposed the bug: ACCEL is field 19, so its tag
     * `(19 << 3) | 2 = 0x9a` has the high bit set and is therefore a **2-byte
     * varint** (`9a 01`). Reading the tag as a single byte made us treat `0x01`
     * as a length, so we took 1 payload byte and discarded the rest.
     *
     * Layout of the first: `80 03 | 9a 01 | 09 | 08 ee 05 10 f2 02 18 f0 4c`
     *   msgid   2-byte tag  len   body: x=0x05ee(1518), y=0x0172(370), z=0x4cf0(19696)
     */
    val ACCEL_REAL_1 = hex("80 03 9a 01 09 08 ee 05 10 f2 02 18 f0 4c")
    val ACCEL_REAL_2 = hex("80 03 9a 01 09 08 82 06 10 fc 02 18 d2 4c")
    val ACCEL_REAL_3 = hex("80 03 9a 01 09 08 be 06 10 f2 02 18 d2 4c")

    /**
     * REAL GYRO messages. Field 20 → tag `(20 << 3) | 2 = 0xa2`, also a 2-byte
     * varint (`a2 01`).
     *
     * Layout of the second: `80 03 | a2 01 | 06 | 08 00 10 00 18 00`
     *   body: x=0, y=0, z=0
     */
    val GYRO_REAL_ZERO = hex("80 03 a2 01 06 08 00 10 00 18 00")
    val GYRO_REAL_1 = hex("80 03 a2 01 06 08 00 10 01 18 00")
    val GYRO_REAL_2 = hex("80 03 a2 01 06 08 02 10 01 18 00")

    /**
     * A message whose declared length exceeds the bytes present — malformed or
     * truncated. The batch walker must bail out cleanly rather than reading past
     * the end. Before the 2026-10-26 fix such a message silently produced a
     * garbage 1-byte "sample".
     */
    val MALFORMED_LEN_TOO_LONG = hex("80 03 9a 01 18")

    /**
     * A message with a tag field >= 16 (2-byte tag) and NO payload — i.e. an
     * empty repeated field. Must be tolerated.
     */
    val EMPTY_ACCEL_FIELD = hex("80 03 9a 01 00")

    /**
     * A synthetic but correctly-framed 3-axis sample.
     *
     * NOTE the tag: field 19 → `(19 << 3) | 2` = 154 = 0x9A, and 0x9A has bit 7
     * set, so the tag is the **2-byte varint `9a 01`**. Getting this wrong was
     * the original bug.
     *
     *   `9a 01`     field 19 (ACCEL), wiretype 2
     *   `07`        body length 7
     *   `08 64`     x = 100
     *   `10 c8 01`  y = 200 (2-byte varint)
     *   `18 2c`     z = 44
     */
    val ACCEL_WELLFORMED = hex("80 03 9a 01 07 08 64 10 c8 01 18 2c")

    private fun hex(s: String): ByteArray =
        s.split(" ").filter { it.isNotBlank() }.map { it.toInt(16).toByte() }.toByteArray()
}
