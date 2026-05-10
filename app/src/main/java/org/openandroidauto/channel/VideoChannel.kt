package org.openandroidauto.channel

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * AV Channel message types (from Wifi.proto AVChannelMessage).
 */
object AVMessageType {
    const val AV_MEDIA_WITH_TIMESTAMP: Int = 0x0000
    const val AV_MEDIA: Int = 0x0001
    const val SETUP_REQUEST: Int = 0x8000
    const val START_INDICATION: Int = 0x8001
    const val STOP_INDICATION: Int = 0x8002
    const val SETUP_RESPONSE: Int = 0x8003
    const val AV_MEDIA_ACK: Int = 0x8004
    const val VIDEO_FOCUS_REQUEST: Int = 0x8007
    const val VIDEO_FOCUS_INDICATION: Int = 0x8008
}

data class VideoConfig(
    val width: Int = 1280,
    val height: Int = 720,
    val fps: Int = 30,
    val dpi: Int = 160,
    val bitrate: Int = 4_000_000
)

/**
 * Callback for sending video frames over the protocol.
 */
interface VideoChannelCallback {
    fun onVideoFrame(channelId: UByte, payload: ByteArray)
    fun onSendMessage(channelId: UByte, payload: ByteArray)
}

/**
 * Manages screen capture via MediaProjection and H.264 encoding via MediaCodec.
 */
class VideoChannel(
    private val channelId: UByte,
    private val callback: VideoChannelCallback
) {
    var config = VideoConfig()
        private set

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var session: Int = 0
    private var running = false

    /**
     * Initialize with a MediaProjection obtained from Activity result.
     */
    fun setMediaProjection(projection: MediaProjection) {
        mediaProjection = projection
    }

    /**
     * Handle incoming AV channel message.
     */
    fun onMessage(messageType: Int, payload: ByteArray) {
        when (messageType) {
            AVMessageType.SETUP_REQUEST -> handleSetupRequest(payload)
            AVMessageType.START_INDICATION -> handleStartIndication(payload)
            AVMessageType.STOP_INDICATION -> handleStopIndication()
            AVMessageType.VIDEO_FOCUS_REQUEST -> handleVideoFocusRequest(payload)
            AVMessageType.AV_MEDIA_ACK -> {} // Flow control ack
        }
    }

    fun stop() {
        running = false
        encoder?.stop()
        encoder?.release()
        encoder = null
        inputSurface?.release()
        inputSurface = null
        virtualDisplay?.release()
        virtualDisplay = null
    }

    // --- Message handlers ---

    private fun handleSetupRequest(payload: ByteArray) {
        // AVChannelSetupRequest: config_index (field 1, varint)
        // Respond with SETUP_RESPONSE: status=OK, max_unacked=5
        val response = buildSetupResponse(maxUnacked = 5, configIndex = 0)
        sendMessage(AVMessageType.SETUP_RESPONSE, response)
    }

    private fun handleStartIndication(payload: ByteArray) {
        // AVChannelStartIndication: session(field 1), config(field 2)
        if (payload.size >= 2) {
            // Simple protobuf parse: field 1 varint
            session = parseVarintField(payload, 1)
        }
        startEncoding()
    }

    private fun handleStopIndication() {
        stop()
    }

    private fun handleVideoFocusRequest(payload: ByteArray) {
        // Respond with VIDEO_FOCUS_INDICATION: focus_mode=FOCUSED, unrequested=false
        val indication = byteArrayOf(
            0x08, 0x01, // field 1 (focus_mode) = FOCUSED(1)
            0x10, 0x00  // field 2 (unrequested) = false
        )
        sendMessage(AVMessageType.VIDEO_FOCUS_INDICATION, indication)
    }

    private fun startEncoding() {
        if (running) return
        val projection = mediaProjection ?: return

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, config.width, config.height).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, config.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
        }

        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = createInputSurface()
            start()
        }

        virtualDisplay = projection.createVirtualDisplay(
            "AndroidAutoVideo",
            config.width, config.height, config.dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface, null, null
        )

        running = true
        startOutputLoop()
    }

    private fun startOutputLoop() {
        Thread {
            val bufferInfo = MediaCodec.BufferInfo()
            while (running) {
                val codec = encoder ?: break
                val index = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                if (index >= 0) {
                    val outputBuffer = codec.getOutputBuffer(index) ?: continue
                    if (bufferInfo.size > 0) {
                        val data = ByteArray(bufferInfo.size)
                        outputBuffer.get(data)
                        sendVideoFrame(data, bufferInfo.presentationTimeUs)
                    }
                    codec.releaseOutputBuffer(index, false)
                }
            }
        }.apply {
            name = "AA-VideoEncoder"
            isDaemon = true
            start()
        }
    }

    /**
     * Package encoded NAL units as AV_MEDIA_WITH_TIMESTAMP_INDICATION.
     * Format: [messageType:2][timestamp:8][data:N]
     */
    private fun sendVideoFrame(nalData: ByteArray, timestampUs: Long) {
        val payload = ByteBuffer.allocate(2 + 8 + nalData.size).order(ByteOrder.BIG_ENDIAN)
            .putShort(AVMessageType.AV_MEDIA_WITH_TIMESTAMP.toShort())
            .putLong(timestampUs)
            .put(nalData)
            .array()
        callback.onVideoFrame(channelId, payload)
    }

    private fun sendMessage(type: Int, protobufPayload: ByteArray) {
        val msg = ByteBuffer.allocate(2 + protobufPayload.size).order(ByteOrder.BIG_ENDIAN)
            .putShort(type.toShort())
            .put(protobufPayload)
            .array()
        callback.onSendMessage(channelId, msg)
    }

    private fun buildSetupResponse(maxUnacked: Int, configIndex: Int): ByteArray {
        // AVChannelSetupResponse protobuf:
        // field 1 (media_status) = OK(2), field 2 (max_unacked), field 3 (configs)
        return byteArrayOf(
            0x08, 0x02,                          // field 1: status = OK(2)
            0x10, maxUnacked.toByte(),            // field 2: max_unacked
            0x18, configIndex.toByte()            // field 3: config index
        )
    }

    private fun parseVarintField(data: ByteArray, fieldNum: Int): Int {
        var i = 0
        while (i < data.size) {
            val tag = data[i].toInt() and 0xFF
            val field = tag ushr 3
            val wireType = tag and 0x07
            i++
            if (wireType == 0) { // varint
                var value = 0
                var shift = 0
                while (i < data.size) {
                    val b = data[i].toInt() and 0xFF
                    i++
                    value = value or ((b and 0x7F) shl shift)
                    if (b and 0x80 == 0) break
                    shift += 7
                }
                if (field == fieldNum) return value
            }
        }
        return 0
    }
}
