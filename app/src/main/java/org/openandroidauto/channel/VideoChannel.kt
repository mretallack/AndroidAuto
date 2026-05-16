package org.openandroidauto.channel

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder

object AVMessageType {
    const val AV_MEDIA_WITH_TIMESTAMP: Int = 0x0000
    const val AV_MEDIA: Int = 0x0001
    const val SETUP_REQUEST: Int = 0x8000
    const val START_INDICATION: Int = 0x8001
    const val STOP_INDICATION: Int = 0x8002
    const val SETUP_RESPONSE: Int = 0x8003  // aka CONFIG
    const val AV_MEDIA_ACK: Int = 0x8004
    const val VIDEO_FOCUS_REQUEST: Int = 0x8007
    const val VIDEO_FOCUS_INDICATION: Int = 0x8008
}

data class VideoConfig(
    val width: Int = 800,
    val height: Int = 480,
    val fps: Int = 30,
    val dpi: Int = 160,
    val bitrate: Int = 2_000_000
)

interface VideoChannelCallback {
    fun onVideoFrame(channelId: UByte, payload: ByteArray)
    fun onSendMessage(channelId: UByte, payload: ByteArray)
}

/**
 * Video channel state machine:
 * IDLE → (channel opened) → SETUP_SENT → (CONFIG received) → CONFIGURED → (FOCUS received) → STARTED
 */
enum class VideoState { IDLE, SETUP_SENT, CONFIGURED, FOCUSED, STARTED }

class VideoChannel(
    private val channelId: UByte,
    private val callback: VideoChannelCallback
) {
    private val TAG = "AAVideo"

    var config = VideoConfig()
        private set
    var state: VideoState = VideoState.IDLE
        private set
    var maxUnacked: Int = 1
        private set
    var sessionId: Int = 1
        private set

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var running = false

    fun setMediaProjection(projection: MediaProjection) {
        mediaProjection = projection
    }

    fun onMessage(messageType: Int, payload: ByteArray) {
        when (messageType) {
            AVMessageType.SETUP_REQUEST -> handleSetupRequest(payload)
            AVMessageType.SETUP_RESPONSE -> handleConfigResponse(payload)
            AVMessageType.START_INDICATION -> handleStartIndication(payload)
            AVMessageType.STOP_INDICATION -> handleStopIndication()
            AVMessageType.VIDEO_FOCUS_REQUEST -> handleVideoFocusRequest(payload)
            AVMessageType.VIDEO_FOCUS_INDICATION -> handleVideoFocusIndication(payload)
            AVMessageType.AV_MEDIA_ACK -> {} // Flow control
            else -> Log.w(TAG, "Unknown video message: 0x${messageType.toString(16)}")
        }
    }

    /** Called after channel opens to send SETUP */
    fun sendSetup() {
        // Setup: field 1 (type) = MEDIA_CODEC_VIDEO_H264_BP (3)
        val payload = byteArrayOf(0x08, 0x03)
        sendMessage(AVMessageType.SETUP_REQUEST, payload)
        state = VideoState.SETUP_SENT
        Log.w(TAG, "Sent SETUP (H264_BP), state=SETUP_SENT")
    }

    /** Send START indication to begin streaming */
    fun sendStart() {
        // Start: field 1 (session_id) varint, field 2 (config) varint
        val payload = byteArrayOf(0x08, sessionId.toByte(), 0x10, 0x00) // session=1, config=0
        sendMessage(AVMessageType.START_INDICATION, payload)
        state = VideoState.STARTED
        Log.w(TAG, "Sent START (session=$sessionId), state=STARTED")
        startEncoding()
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
        state = VideoState.IDLE
    }

    // --- Message handlers ---

    private fun handleSetupRequest(payload: ByteArray) {
        // Head unit asking us to setup (we're acting as sink in this case)
        val response = byteArrayOf(
            0x08, 0x02, // status = STATUS_READY(2)
            0x10, 0x05, // max_unacked = 5
            0x18, 0x00  // config index = 0
        )
        sendMessage(AVMessageType.SETUP_RESPONSE, response)
    }

    private fun handleConfigResponse(payload: ByteArray) {
        // CONFIG (0x8003) from head unit after our SETUP
        // Fields: status(1), max_unacked(2), configuration_indices(3)
        var status = 0
        var i = 0
        while (i < payload.size) {
            val tag = payload[i].toInt() and 0xFF; i++
            val field = tag ushr 3
            val wireType = tag and 0x07
            if (wireType == 0) {
                var value = 0; var shift = 0
                while (i < payload.size) {
                    val b = payload[i].toInt() and 0xFF; i++
                    value = value or ((b and 0x7F) shl shift)
                    if (b and 0x80 == 0) break
                    shift += 7
                }
                when (field) {
                    1 -> status = value
                    2 -> maxUnacked = value
                    3 -> {} // config index
                }
            } else break
        }
        Log.w(TAG, "CONFIG received: status=$status max_unacked=$maxUnacked")
        if (status == 2) { // STATUS_READY
            state = VideoState.CONFIGURED
            Log.w(TAG, "Video configured, waiting for FOCUS")
        }
    }

    private fun handleVideoFocusIndication(payload: ByteArray) {
        // VideoFocusNotification: field 1 (focus) varint, field 2 (unsolicited) varint
        var focusMode = 0
        var i = 0
        while (i < payload.size) {
            val tag = payload[i].toInt() and 0xFF; i++
            val field = tag ushr 3
            var value = 0; var shift = 0
            while (i < payload.size) {
                val b = payload[i].toInt() and 0xFF; i++
                value = value or ((b and 0x7F) shl shift)
                if (b and 0x80 == 0) break
                shift += 7
            }
            if (field == 1) focusMode = value
        }
        Log.w(TAG, "VIDEO_FOCUS_NOTIFICATION: mode=$focusMode (1=PROJECTED)")
        if (focusMode == 1 && (state == VideoState.CONFIGURED || state == VideoState.SETUP_SENT)) {
            state = VideoState.FOCUSED
            // We have focus - send START
            sendStart()
        }
    }

    private fun handleVideoFocusRequest(payload: ByteArray) {
        // Phone receives focus request - respond with focus indication
        val indication = byteArrayOf(0x08, 0x01, 0x10, 0x00) // PROJECTED, unsolicited=false
        sendMessage(AVMessageType.VIDEO_FOCUS_INDICATION, indication)
    }

    private fun handleStartIndication(payload: ByteArray) {
        if (payload.size >= 2) {
            sessionId = parseVarintField(payload, 1)
        }
        state = VideoState.STARTED
        startEncoding()
    }

    private fun handleStopIndication() {
        stop()
    }

    private fun startEncoding() {
        if (running) return
        val projection = mediaProjection
        if (projection == null) {
            Log.w(TAG, "No MediaProjection - sending black frames")
            startBlackFrameLoop()
            return
        }

        try {
            // Android 14+ requires registering a callback before capture
            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.w(TAG, "MediaProjection stopped")
                    stop()
                }
            }, null)

            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, config.width, config.height).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, config.fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
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
            Log.w(TAG, "Encoding started: ${config.width}x${config.height}@${config.fps}fps")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start encoding: ${e.message}, falling back to black frames")
            startBlackFrameLoop()
        }
    }

    /**
     * When no MediaProjection is available, send minimal H.264 black frames
     * so the head unit doesn't timeout.
     */
    private fun startBlackFrameLoop() {
        running = true
        Thread {
            // Minimal H.264 SPS+PPS+IDR for 800x480 black frame
            val sps = byteArrayOf(
                0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0xC0.toByte(), 0x1E,
                0xD9.toByte(), 0x00, 0xA0.toByte(), 0x47, 0xFE.toByte(), 0xC8.toByte()
            )
            val pps = byteArrayOf(
                0x00, 0x00, 0x00, 0x01, 0x68, 0xCE.toByte(), 0x38, 0x80.toByte()
            )
            // Minimal IDR slice (black)
            val idr = byteArrayOf(
                0x00, 0x00, 0x00, 0x01, 0x65, 0x88.toByte(), 0x80.toByte(), 0x40,
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
            )
            val frame = sps + pps + idr
            var timestampUs = 0L
            val frameDurationUs = 1_000_000L / config.fps

            // Send SPS+PPS first as codec config
            sendVideoFrame(sps + pps, 0)

            while (running) {
                sendVideoFrame(frame, timestampUs)
                timestampUs += frameDurationUs
                Thread.sleep(frameDurationUs / 1000)
            }
        }.apply {
            name = "AA-BlackFrames"
            isDaemon = true
            start()
        }
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

    private fun sendVideoFrame(nalData: ByteArray, timestampUs: Long) {
        // AV_MEDIA_WITH_TIMESTAMP: [type:2][timestamp:8][data:N]
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

    private fun parseVarintField(data: ByteArray, fieldNum: Int): Int {
        var i = 0
        while (i < data.size) {
            val tag = data[i].toInt() and 0xFF
            val field = tag ushr 3
            i++
            var value = 0; var shift = 0
            while (i < data.size) {
                val b = data[i].toInt() and 0xFF; i++
                value = value or ((b and 0x7F) shl shift)
                if (b and 0x80 == 0) break
                shift += 7
            }
            if (field == fieldNum) return value
        }
        return 0
    }
}
