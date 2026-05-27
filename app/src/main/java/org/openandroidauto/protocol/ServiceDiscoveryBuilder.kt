package org.openandroidauto.protocol

import java.io.ByteArrayOutputStream

/**
 * Builds a ServiceDiscoveryResponse protobuf for advertising our channels.
 * Uses manual protobuf encoding (no generated code dependency for this message).
 */
object ServiceDiscoveryBuilder {

    /**
     * Build a complete ServiceDiscoveryResponse with video, audio, input, sensor, and bluetooth channels.
     */
    fun build(
        headUnitName: String = "Open Android Auto",
        carModel: String = "Generic",
        carYear: String = "2024",
        carSerial: String = "OAA-001",
        manufacturer: String = "OpenAndroidAuto",
        model: String = "Phone",
        swBuild: String = "1",
        swVersion: String = "0.1.0",
        bluetoothAddress: String? = null
    ): ByteArray {
        val out = ByteArrayOutputStream()

        // Channel descriptors (field 1, repeated)
        // Video channel (id=1)
        writeField(out, 1, buildVideoChannelDescriptor())
        // Media audio channel (id=3)
        writeField(out, 1, buildAudioChannelDescriptor(channelId = 3, audioType = 3)) // MEDIA
        // Input channel (id=2)
        writeField(out, 1, buildInputChannelDescriptor())
        // Sensor channel (id=4)
        writeField(out, 1, buildSensorChannelDescriptor())
        // Bluetooth channel (id=5)
        if (bluetoothAddress != null) {
            writeField(out, 1, buildBluetoothChannelDescriptor(bluetoothAddress))
        }

        // head_unit_name (field 2)
        writeStringField(out, 2, headUnitName)
        // car_model (field 3)
        writeStringField(out, 3, carModel)
        // car_year (field 4)
        writeStringField(out, 4, carYear)
        // car_serial (field 5)
        writeStringField(out, 5, carSerial)
        // left_hand_drive_vehicle (field 6)
        writeVarintField(out, 6, 1) // true
        // headunit_manufacturer (field 7)
        writeStringField(out, 7, manufacturer)
        // headunit_model (field 8)
        writeStringField(out, 8, model)
        // sw_build (field 9)
        writeStringField(out, 9, swBuild)
        // sw_version (field 10)
        writeStringField(out, 10, swVersion)
        // can_play_native_media_during_vr (field 11)
        writeVarintField(out, 11, 1) // true

        return out.toByteArray()
    }

    private fun buildVideoChannelDescriptor(): ByteArray {
        val out = ByteArrayOutputStream()
        // channel_id (field 1) = 1
        writeVarintField(out, 1, 1)
        // av_channel (field 3) - video config
        writeField(out, 3, buildAvChannel())
        return out.toByteArray()
    }

    private fun buildAvChannel(): ByteArray {
        val out = ByteArrayOutputStream()
        // stream_type (field 1) = VIDEO(3)
        writeVarintField(out, 1, 3)
        // video_configs (field 4, repeated) - 720p 30fps
        writeField(out, 4, buildVideoConfig(2, 1, 0, 0, 160)) // 720p, 30fps
        writeField(out, 4, buildVideoConfig(1, 1, 0, 0, 160)) // 480p, 30fps
        return out.toByteArray()
    }

    private fun buildVideoConfig(resolution: Int, fps: Int, marginW: Int, marginH: Int, dpi: Int): ByteArray {
        val out = ByteArrayOutputStream()
        writeVarintField(out, 1, resolution) // video_resolution
        writeVarintField(out, 2, fps)        // video_fps
        writeVarintField(out, 3, marginW)    // margin_width
        writeVarintField(out, 4, marginH)    // margin_height
        writeVarintField(out, 5, dpi)        // dpi
        return out.toByteArray()
    }

    private fun buildAudioChannelDescriptor(channelId: Int, audioType: Int): ByteArray {
        val out = ByteArrayOutputStream()
        writeVarintField(out, 1, channelId)
        writeField(out, 3, buildAudioAvChannel(audioType))
        return out.toByteArray()
    }

    private fun buildAudioAvChannel(audioType: Int): ByteArray {
        val out = ByteArrayOutputStream()
        // stream_type (field 1) = AUDIO(1)
        writeVarintField(out, 1, 1)
        // audio_type (field 2)
        writeVarintField(out, 2, audioType)
        // audio_configs (field 3) - 48kHz stereo 16-bit
        writeField(out, 3, buildAudioConfig(48000, 16, 2))
        return out.toByteArray()
    }

    private fun buildAudioConfig(sampleRate: Int, bitDepth: Int, channels: Int): ByteArray {
        val out = ByteArrayOutputStream()
        writeVarintField(out, 1, sampleRate)
        writeVarintField(out, 2, bitDepth)
        writeVarintField(out, 3, channels)
        return out.toByteArray()
    }

    private fun buildInputChannelDescriptor(): ByteArray {
        val out = ByteArrayOutputStream()
        writeVarintField(out, 1, 2) // channel_id = 2
        // input_channel (field 4)
        writeField(out, 4, buildInputChannel())
        return out.toByteArray()
    }

    private fun buildInputChannel(): ByteArray {
        val out = ByteArrayOutputStream()
        // touch_screen_config (field 2) - 800x480
        writeField(out, 2, buildTouchConfig(800, 480))
        return out.toByteArray()
    }

    private fun buildTouchConfig(width: Int, height: Int): ByteArray {
        val out = ByteArrayOutputStream()
        writeVarintField(out, 1, width)
        writeVarintField(out, 2, height)
        return out.toByteArray()
    }

    private fun buildSensorChannelDescriptor(): ByteArray {
        val out = ByteArrayOutputStream()
        writeVarintField(out, 1, 4) // channel_id = 4
        // sensor_channel (field 2)
        writeField(out, 2, buildSensorChannel())
        return out.toByteArray()
    }

    private fun buildSensorChannel(): ByteArray {
        val out = ByteArrayOutputStream()
        // sensors (field 1, repeated) - each Sensor has type (field 1)
        writeField(out, 1, sensorEntry(10)) // NIGHT_DATA
        writeField(out, 1, sensorEntry(13)) // DRIVING_STATUS
        writeField(out, 1, sensorEntry(1))  // LOCATION
        return out.toByteArray()
    }

    private fun sensorEntry(type: Int): ByteArray {
        val out = ByteArrayOutputStream()
        writeVarintField(out, 1, type)
        return out.toByteArray()
    }

    private fun buildBluetoothChannelDescriptor(btAddress: String): ByteArray {
        val out = ByteArrayOutputStream()
        writeVarintField(out, 1, 5) // channel_id = 5
        // bluetooth_service (field 6)
        writeField(out, 6, buildBluetoothService(btAddress))
        return out.toByteArray()
    }

    private fun buildBluetoothService(btAddress: String): ByteArray {
        val out = ByteArrayOutputStream()
        // car_address (field 1) = phone's BT MAC address
        writeStringField(out, 1, btAddress)
        // supported_pairing_methods (field 2, repeated varint)
        writeVarintField(out, 2, 1) // HFP
        writeVarintField(out, 2, 2) // A2DP
        return out.toByteArray()
    }

    // --- Protobuf encoding helpers ---

    private fun writeVarintField(out: ByteArrayOutputStream, fieldNum: Int, value: Int) {
        out.write(makeTag(fieldNum, 0)) // wire type 0 = varint
        writeVarint(out, value.toLong())
    }

    private fun writeStringField(out: ByteArrayOutputStream, fieldNum: Int, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeField(out, fieldNum, bytes)
    }

    private fun writeField(out: ByteArrayOutputStream, fieldNum: Int, value: ByteArray) {
        out.write(makeTag(fieldNum, 2)) // wire type 2 = length-delimited
        writeVarint(out, value.size.toLong())
        out.write(value)
    }

    private fun makeTag(fieldNum: Int, wireType: Int): Int = (fieldNum shl 3) or wireType

    private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (v > 0x7F) {
            out.write(((v.toInt() and 0x7F) or 0x80))
            v = v ushr 7
        }
        out.write(v.toInt() and 0x7F)
    }
}
