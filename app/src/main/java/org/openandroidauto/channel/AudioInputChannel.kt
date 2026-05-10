package org.openandroidauto.channel

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Receives audio from head unit microphone and plays it on the phone.
 * Used for voice commands / phone calls.
 */
class AudioInputChannel(
    private val channelId: UByte,
    private val callback: VideoChannelCallback
) {
    companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNEL_COUNT = 1
    }

    private var audioTrack: AudioTrack? = null
    private var session = 0

    fun onMessage(messageType: Int, payload: ByteArray) {
        when (messageType) {
            AVMessageType.AV_MEDIA_WITH_TIMESTAMP -> handleAudioData(payload)
            AVMessageType.AV_MEDIA -> handleAudioData(payload)
            0x8005 -> handleOpenRequest(payload) // AV_INPUT_OPEN_REQUEST
            AVMessageType.STOP_INDICATION -> stop()
        }
    }

    fun stop() {
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
    }

    private fun handleOpenRequest(payload: ByteArray) {
        // Create AudioTrack for playback
        val bufSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build())
            .setBufferSizeInBytes(bufSize)
            .build()
        audioTrack?.play()

        // Send AV_INPUT_OPEN_RESPONSE: session=1, value=5 (max_unacked)
        val response = byteArrayOf(0x08, 0x01, 0x10, 0x05)
        sendMessage(0x8006, response) // AV_INPUT_OPEN_RESPONSE
    }

    private fun handleAudioData(payload: ByteArray) {
        // Payload is raw PCM after the timestamp (8 bytes)
        if (payload.size <= 8) return
        val pcmData = payload.copyOfRange(8, payload.size)
        audioTrack?.write(pcmData, 0, pcmData.size)

        // Send ACK
        val ack = byteArrayOf(0x08, session.toByte(), 0x10, 0x01)
        sendMessage(AVMessageType.AV_MEDIA_ACK, ack)
    }

    private fun sendMessage(type: Int, protobufPayload: ByteArray) {
        val msg = ByteBuffer.allocate(2 + protobufPayload.size).order(ByteOrder.BIG_ENDIAN)
            .putShort(type.toShort())
            .put(protobufPayload)
            .array()
        callback.onSendMessage(channelId, msg)
    }
}
