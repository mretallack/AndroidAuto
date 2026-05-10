package org.openandroidauto.channel

import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Captures phone audio via AudioPlaybackCapture and sends as PCM to head unit.
 * 48kHz stereo 16-bit PCM.
 */
class AudioOutputChannel(
    private val channelId: UByte,
    private val callback: VideoChannelCallback // reuse same callback interface
) {
    companion object {
        const val SAMPLE_RATE = 48000
        const val CHANNEL_COUNT = 2
        const val BIT_DEPTH = 16
        const val BUFFER_SIZE = SAMPLE_RATE * CHANNEL_COUNT * (BIT_DEPTH / 8) / 10 // 100ms
    }

    private var audioRecord: AudioRecord? = null
    private var running = false
    private var session = 0

    fun onMessage(messageType: Int, payload: ByteArray) {
        when (messageType) {
            AVMessageType.SETUP_REQUEST -> handleSetupRequest()
            AVMessageType.START_INDICATION -> handleStartIndication(payload)
            AVMessageType.STOP_INDICATION -> stop()
            AVMessageType.AV_MEDIA_ACK -> {} // flow control
        }
    }

    fun setMediaProjection(projection: MediaProjection) {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(android.media.AudioAttributes.USAGE_GAME)
            .build()

        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()

        audioRecord = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(config)
            .setAudioFormat(format)
            .setBufferSizeInBytes(BUFFER_SIZE * 2)
            .build()
    }

    fun stop() {
        running = false
        audioRecord?.stop()
    }

    private fun handleSetupRequest() {
        val response = byteArrayOf(
            0x08, 0x02, // status = OK(2)
            0x10, 0x05, // max_unacked = 5
            0x18, 0x00  // config index = 0
        )
        sendMessage(AVMessageType.SETUP_RESPONSE, response)
    }

    private fun handleStartIndication(payload: ByteArray) {
        if (running) return
        running = true
        audioRecord?.startRecording()
        startCaptureLoop()
    }

    private fun startCaptureLoop() {
        Thread {
            val buffer = ByteArray(BUFFER_SIZE)
            var timestampUs = 0L
            val frameDurationUs = (buffer.size.toLong() * 1_000_000) / (SAMPLE_RATE * CHANNEL_COUNT * 2)

            while (running) {
                val record = audioRecord ?: break
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) {
                    sendAudioFrame(buffer.copyOf(read), timestampUs)
                    timestampUs += frameDurationUs
                }
            }
        }.apply {
            name = "AA-AudioCapture"
            isDaemon = true
            start()
        }
    }

    private fun sendAudioFrame(pcmData: ByteArray, timestampUs: Long) {
        val payload = ByteBuffer.allocate(2 + 8 + pcmData.size).order(ByteOrder.BIG_ENDIAN)
            .putShort(AVMessageType.AV_MEDIA_WITH_TIMESTAMP.toShort())
            .putLong(timestampUs)
            .put(pcmData)
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
}
