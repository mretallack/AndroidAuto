package org.openandroidauto.channel

import org.openandroidauto.util.FileLogger
import org.openandroidauto.util.hex

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import org.openandroidauto.ServiceState
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

/**
 * Video configuration. Defaults follow the HUIG guaranteed baseline:
 * 800x480 @ 30 fps, H.264 Baseline Profile level 3.1 (§9b in
 * docs/connection-stability.md). Non-standard resolutions cause "significant
 * performance loss" per the spec, so we only ever advertise/encode this one.
 */
data class VideoConfig(
    val width: Int = 800,
    val height: Int = 480,
    val fps: Int = 30,
    val dpi: Int = 160,
    val bitrate: Int = 250_000
) {
    companion object {
        /**
         * aasdk VideoResolutionEnum: NONE=0, _480p=1, _720p=2, _1080p=3.
         * There is no explicit 800x480 entry — `_480p` is the 800x480 decode
         * size and the HUIG's required baseline. We only advertise/support it.
         */
        const val RESOLUTION_480p = 1

        /** The single configuration we advertise and encode. */
        fun baseline() = VideoConfig()
    }
}

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
    // Index chosen by the HU in its CONFIG message; echoed in START (§9b).
    var selectedConfigIndex: Int = VideoConfig.RESOLUTION_480p
        private set

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var running = false
    private var unackedFrames = 0
    private val unackedLock = Object()

    /**
     * Video focus state. The HU owns the screen and tells us when it wants it back
     * via VIDEO_FOCUS_NOTIFICATION (0x8008): FOCUSED(1) or UNFOCUSED(2). When it
     * takes the display (e.g. the reversing camera) we MUST stop sending frames —
     * otherwise our H.264 stream corrupts whatever the HU is showing. Symptom
     * before this fix: digital corruption of the reversing-camera image
     * (observed 2026-10-19, HUIG: MD yields video focus on revocation).
     */
    @Volatile private var videoFocusHeld = false
    private val videoFocusLock = Object()

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
            AVMessageType.AV_MEDIA_ACK -> handleMediaAck(payload)
            else -> FileLogger.w(TAG, "UNHANDLED video message type=0x${messageType.toString(16)} " +
                "len=${payload.size} payload=${payload.hex(64)}")
        }
    }

    /** Called after channel opens to send SETUP */
    fun sendSetup() {
        // Setup: field 1 (type) = MEDIA_CODEC_VIDEO_H264_BP (3)
        val payload = byteArrayOf(0x08, 0x03)
        sendMessage(AVMessageType.SETUP_REQUEST, payload)
        state = VideoState.SETUP_SENT
        FileLogger.w(TAG, "Sent SETUP (H264_BP), state=SETUP_SENT")
    }

    /** Send START indication to begin streaming */
    fun sendStart() {
        // Start: field 1 (session_id) varint, field 2 (configuration index) varint.
        // The index must echo the HU's choice from its CONFIG message (§9b), not a
        // hardcoded 0 — the HU sends a prioritised list and expects us to confirm.
        val payload = byteArrayOf(0x08, sessionId.toByte(), 0x10, selectedConfigIndex.toByte())
        sendMessage(AVMessageType.START_INDICATION, payload)
        state = VideoState.STARTED
        FileLogger.w(TAG, "Sent START (session=$sessionId, configIndex=$selectedConfigIndex), state=STARTED")
        startEncoding()
    }

    fun stop() {
        running = false
        synchronized(videoFocusLock) { videoFocusHeld = false }
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
                    3 -> selectedConfigIndex = value // HU's chosen index (we send it back in START)
                }
            } else break
        }
        FileLogger.w(TAG, "CONFIG received: status=$status max_unacked=$maxUnacked configIndex=$selectedConfigIndex")
        if (status == 2) { // STATUS_READY
            state = VideoState.CONFIGURED
            FileLogger.w(TAG, "Video configured, waiting for FOCUS")
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
        // VideoFocusMode: NONE=0, FOCUSED=1, UNFOCUSED=2
        FileLogger.w(TAG, "VIDEO_FOCUS_NOTIFICATION: mode=$focusMode (1=FOCUSED, 2=UNFOCUSED)")
        when (focusMode) {
            1 -> { // FOCUSED — we own the screen
                synchronized(videoFocusLock) { videoFocusHeld = true }
                if (state == VideoState.CONFIGURED || state == VideoState.SETUP_SENT) {
                    state = VideoState.FOCUSED
                    sendStart()
                } else {
                    // Re-gained focus mid-session (e.g. reversing camera ended).
                    // Nothing to re-send: the encoder loop resumes from the flag.
                    FileLogger.w(TAG, "Video focus regained — resuming frame output")
                }
            }
            2 -> { // UNFOCUSED — HU has taken the screen; STOP sending immediately
                synchronized(videoFocusLock) { videoFocusHeld = false }
                FileLogger.w(TAG, "Video focus lost — pausing frame output (HU owns the screen)")
            }
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
        // Use test pattern mode - generates valid H.264 at exact 30fps
        // This bypasses MediaProjection to isolate video stability issues
        startTestPatternEncoding()
    }

    /**
     * Generate a test pattern using MediaCodec with programmatic Surface drawing.
     * Produces valid H.264 Baseline at exact 30fps without needing MediaProjection.
     */
    private fun startTestPatternEncoding() {
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, config.width, config.height).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, config.fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                // HUIG: H.264 Baseline Profile required, only I and P frames (no B-frames)
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
            }

            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                inputSurface = createInputSurface()
                start()
            }

            running = true

            // Draw thread - renders test pattern to encoder surface at exact 30fps
            Thread {
                val canvas = inputSurface!!
                val paint = android.graphics.Paint()
                var frameNum = 0
                val frameIntervalMs = 1000L / config.fps

                // Draw static color bars once - only update frame counter every 100 frames
                val colors = intArrayOf(
                    0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFFFFFF00.toInt(),
                    0xFFFF00FF.toInt(), 0xFF00FFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFF000000.toInt()
                )
                val barWidth = config.width / 8

                // Event-driven: when enabled, only post a frame when the rendered
                // content actually changes, plus a periodic idle frame so the HU
                // decoder does not starve (HUIG: send "enough idle frames").
                val eventDriven = ServiceState.eventDrivenVideo.value
                val idleIntervalMs = 2000L   // idle frame cadence (>= 5fps floor is for changes)
                var lastPostMs = 0L
                var lastCounterShown = -1

                while (running) {
                    val startTime = System.currentTimeMillis()
                    val surface = inputSurface ?: break

                    // What would we draw? The counter only visibly changes every 100 frames.
                    val counterShown = frameNum / 100 * 100
                    val contentChanged = counterShown != lastCounterShown
                    val needIdle = (startTime - lastPostMs) >= idleIntervalMs

                    if (!eventDriven || contentChanged || needIdle) {
                        val c = surface.lockCanvas(null)
                        if (c != null) {
                            // Draw static color bars
                            for (i in 0 until 8) {
                                paint.color = colors[i]
                                c.drawRect(
                                    (i * barWidth).toFloat(), 0f,
                                    ((i + 1) * barWidth).toFloat(), config.height.toFloat(), paint
                                )
                            }
                            paint.color = 0xFF000000.toInt()
                            c.drawRect(0f, 30f, 250f, 70f, paint) // clear text area
                            paint.color = 0xFFFFFFFF.toInt()
                            paint.textSize = 40f
                            c.drawText("Frame: $counterShown", 20f, 60f, paint)
                            surface.unlockCanvasAndPost(c)
                            lastCounterShown = counterShown
                            lastPostMs = startTime
                        }
                    }
                    frameNum++

                    // Pace only the content-change frames; idle ticks are cheap.
                    val elapsed = System.currentTimeMillis() - startTime
                    val sleepMs = frameIntervalMs - elapsed
                    if (sleepMs > 0) Thread.sleep(if (eventDriven && !needIdle) 20L else sleepMs)
                }
            }.apply {
                name = "AA-TestPattern"
                isDaemon = true
                start()
            }

            // Output loop - reads encoded frames and sends them
            startOutputLoop()
            FileLogger.w(TAG, "Test pattern encoding started: ${config.width}x${config.height}@${config.fps}fps")
        } catch (e: Exception) {
            FileLogger.e(TAG, "Failed to start test pattern: ${e.message}")
            startBlackFrameLoop()
        }
    }

    private fun startRealEncoding() {
        val projection = mediaProjection ?: return
        try {
            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    FileLogger.w(TAG, "MediaProjection stopped")
                    stop()
                }
            }, null)

            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, config.width, config.height).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, config.fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                // HUIG: H.264 Baseline Profile required, only I and P frames (no B-frames)
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
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
            FileLogger.w(TAG, "Real encoding started: ${config.width}x${config.height}@${config.fps}fps")
        } catch (e: Exception) {
            FileLogger.e(TAG, "Failed to start encoding: ${e.message}, falling back to test pattern")
            startTestPatternEncoding()
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
            try {
            val bufferInfo = MediaCodec.BufferInfo()
            var firstTimestampUs = -1L
            var codecConfig: ByteArray? = null
            while (running) {
                val codec = encoder ?: break
                val index = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                if (index >= 0) {
                    val outputBuffer = codec.getOutputBuffer(index) ?: continue
                    if (bufferInfo.size > 0) {
                        val data = ByteArray(bufferInfo.size)
                        outputBuffer.get(data)

                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            codecConfig = ensureAnnexB(data)
                            FileLogger.w(TAG, "Stored codec config: ${codecConfig!!.size} bytes")
                            codec.releaseOutputBuffer(index, false)
                            continue
                        }

                        if (firstTimestampUs < 0) firstTimestampUs = bufferInfo.presentationTimeUs
                        val relativeTimestampUs = bufferInfo.presentationTimeUs - firstTimestampUs

                        val annexBData = ensureAnnexB(data)
                        val frameData = if (codecConfig != null &&
                            (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)) {
                            codecConfig!! + annexBData
                        } else annexBData

                        sendVideoFrame(frameData, relativeTimestampUs)
                    }
                    codec.releaseOutputBuffer(index, false)
                }
            }
            } catch (e: Exception) {
                FileLogger.w(TAG, "Encoder loop ended: ${e.message}")
            }
        }.apply {
            name = "AA-VideoEncoder"
            isDaemon = true
            start()
        }
    }

    /** Send as MediaIndication (type 0x0001) — no timestamp, used for codec config */
    private fun sendMediaIndication(data: ByteArray) {
        val payload = ByteBuffer.allocate(2 + data.size).order(ByteOrder.BIG_ENDIAN)
            .putShort(AVMessageType.AV_MEDIA.toShort()) // 0x0001 = MediaIndication
            .put(data)
            .array()
        callback.onVideoFrame(channelId, payload)
    }

    /**
     * Ensure H.264 data is in Annex B format (start code prefixed: 00 00 00 01).
     * MediaCodec may output AVCC format (4-byte length prefix) on some devices.
     */
    private fun ensureAnnexB(data: ByteArray): ByteArray {
        if (data.size < 4) return data
        // Check if already Annex B (starts with 00 00 00 01 or 00 00 01)
        if (data[0] == 0.toByte() && data[1] == 0.toByte() &&
            (data[2] == 1.toByte() || (data[2] == 0.toByte() && data[3] == 1.toByte()))) {
            return data
        }
        // Convert AVCC (length-prefixed) to Annex B (start-code-prefixed)
        val output = java.io.ByteArrayOutputStream(data.size + 32)
        var i = 0
        while (i + 4 <= data.size) {
            val nalLen = ((data[i].toInt() and 0xFF) shl 24) or
                ((data[i + 1].toInt() and 0xFF) shl 16) or
                ((data[i + 2].toInt() and 0xFF) shl 8) or
                (data[i + 3].toInt() and 0xFF)
            i += 4
            if (nalLen <= 0 || i + nalLen > data.size) break
            output.write(byteArrayOf(0x00, 0x00, 0x00, 0x01))
            output.write(data, i, nalLen)
            i += nalLen
        }
        return if (output.size() > 0) output.toByteArray() else data
    }

    /**
     * Budget summary for logging on teardown (§16 item 3). Frames/bytes are the
     * cumulative totals the HU was asked to accept; acked is how many it confirmed.
     */
    fun budgetSummary(): String =
        "videoFramesSent=$framesSent videoBytesSent=$bytesSent " +
        "videoFramesAcked=$framesAcked videoBytesAcked=$bytesAcked " +
        "focusSuppressed=$framesSuppressedForFocus focusHeld=$videoFocusHeld " +
        "unacked=$unackedFrames maxUnacked=$maxUnacked"

    private fun handleMediaAck(payload: ByteArray) {
        framesAcked++
        synchronized(unackedLock) {
            unackedFrames = (unackedFrames - 1).coerceAtLeast(0)
            (unackedLock as Object).notifyAll()
        }
    }

    // ── Ack/byte budget instrumentation (§16 item 3) ──
    // Hypothesis: the HU enforces a cumulative frame/byte budget and drops VBUS
    // when exceeded ("more data → faster death"). These counters let us compare
    // frames/bytes accepted vs time-to-disconnect across sessions.
    @Volatile var framesSent: Long = 0L; private set
    @Volatile var bytesSent: Long = 0L; private set
    @Volatile var framesAcked: Long = 0L; private set
    @Volatile var bytesAcked: Long = 0L; private set
    /** Frames dropped because the HU held video focus (e.g. reversing camera). */
    @Volatile var framesSuppressedForFocus: Long = 0L; private set

    /** Test seam: lets unit tests drive the focus gate directly. */
    internal fun sendVideoFrameForTest(nalData: ByteArray, timestampUs: Long) =
        sendVideoFrame(nalData, timestampUs)

    private fun sendVideoFrame(nalData: ByteArray, timestampUs: Long) {
        // Yield the screen while the HU has taken video focus (reversing camera,
        // native UI, etc). Sending frames then corrupts what the HU is displaying.
        if (!videoFocusHeld) {
            framesSuppressedForFocus++
            return
        }
        framesSent++
        bytesSent += nalData.size
        // Flow control: wait if too many unacked frames
        synchronized(unackedLock) {
            if (unackedFrames >= maxUnacked) {
                FileLogger.w(TAG, "Flow control: waiting (unacked=$unackedFrames, max=$maxUnacked)")
                ServiceState.addEvent("FLOW CTRL: blocked at $unackedFrames")
            }
            while (unackedFrames >= maxUnacked && running) {
                try { (unackedLock as Object).wait(100) } catch (_: InterruptedException) {}
            }
            unackedFrames++
        }
        if (!running) return

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
