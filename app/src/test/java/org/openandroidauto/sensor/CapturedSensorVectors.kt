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
     * The anomalous ACCEL/GYRO cases.
     *
     * Observed: the message frame declared ~20-38 bytes, but the *inner* payload
     * handed to the parser was a single byte (`18` for GYRO, `19` for ACCEL).
     *
     * These are modelled here as the outer tag (field 19/20, wiretype 2) with a
     * 1-byte body, which is what the parser actually received. A well-formed
     * `Accel` body needs three varints (`08 x 10 y 18 z`), so one byte can never
     * be a valid sample — which is the point of the test.
     *
     * NOTE: a 1-byte body of `18` decodes as field 3 wiretype 0 — i.e. it looks
     * like the *tail* of a real Accel message. That is the evidence that this is
     * more likely truncation on our side than a head-unit stub.
     */
    val ACCEL_STUB_ONE_BYTE = hex("80 03 9a 01 18")
    val GYRO_STUB_ONE_BYTE = hex("80 03 a2 01 18")

    /**
     * A well-formed 3-axis sample, for comparison.
     * Outer: `9a 07` = field 19 (ACCEL) wiretype 2, body length 7.
     * Body:  `08 64`  x = 100
     *        `10 c8 01` y = 200  (2-byte varint)
     *        `18 2c`  z = 44
     */
    val ACCEL_WELLFORMED = hex("80 03 9a 07 08 64 10 c8 01 18 2c")

    private fun hex(s: String): ByteArray =
        s.split(" ").filter { it.isNotBlank() }.map { it.toInt(16).toByte() }.toByteArray()
}
