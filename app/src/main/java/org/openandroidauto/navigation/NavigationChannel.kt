package org.openandroidauto.navigation

import org.openandroidauto.channel.VideoChannelCallback
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Navigation channel message types.
 */
object NavMessageType {
    const val STATUS: Int = 0x8003
    const val TURN_EVENT: Int = 0x8004
    const val DISTANCE_EVENT: Int = 0x8005
}

data class NavigationTurn(
    val streetName: String,
    val direction: Int, // ManeuverDirection enum
    val type: Int,      // ManeuverType enum
    val image: ByteArray? = null,
    val roundaboutExit: Int = 0
)

data class NavigationDistance(
    val meters: Int,
    val timeToStepSeconds: Int,
    val distanceMillis: Int,
    val unit: Int // DistanceUnit enum
)

/**
 * Receives turn-by-turn navigation data and forwards to head unit.
 * Also handles navigation focus requests from the protocol engine.
 */
class NavigationChannel(
    private val channelId: UByte,
    private val callback: VideoChannelCallback
) {
    private var isActive = false

    fun onMessage(messageType: Int, payload: ByteArray) {
        // Head unit doesn't typically send nav messages to phone
        // This channel is phone→head unit
    }

    /**
     * Send navigation status update.
     */
    fun sendStatus(active: Boolean) {
        isActive = active
        val status = if (active) 1 else 2 // ACTIVE=1, INACTIVE=2
        val payload = byteArrayOf(0x08, status.toByte())
        sendMessage(NavMessageType.STATUS, payload)
    }

    /**
     * Send a turn event to the head unit.
     */
    fun sendTurnEvent(turn: NavigationTurn) {
        if (!isActive) return
        val nameBytes = turn.streetName.toByteArray()
        val buf = ByteBuffer.allocate(20 + nameBytes.size)
        buf.put(0x0A.toByte()).put(nameBytes.size.toByte()).put(nameBytes) // field 1: street_name
        buf.put(0x10.toByte()).put(turn.direction.toByte())                // field 2: direction
        buf.put(0x18.toByte()).put(turn.type.toByte())                     // field 3: type
        if (turn.image != null) {
            buf.put(0x22.toByte()).put(turn.image.size.toByte()).put(turn.image) // field 4: image
        }
        buf.put(0x28.toByte()).put(turn.roundaboutExit.toByte())           // field 5: roundabout exit
        buf.put(0x30.toByte()).put(0x00.toByte())                          // field 6: roundabout angle

        sendMessage(NavMessageType.TURN_EVENT, buf.array().copyOf(buf.position()))
    }

    /**
     * Send distance to next maneuver.
     */
    fun sendDistanceEvent(distance: NavigationDistance) {
        if (!isActive) return
        val buf = ByteBuffer.allocate(20)
        buf.put(0x08.toByte()).put(encodeVarint(distance.meters))
        buf.put(0x10.toByte()).put(encodeVarint(distance.timeToStepSeconds))
        buf.put(0x18.toByte()).put(encodeVarint(distance.distanceMillis))
        buf.put(0x20.toByte()).put(distance.unit.toByte())

        sendMessage(NavMessageType.DISTANCE_EVENT, buf.array().copyOf(buf.position()))
    }

    private fun sendMessage(type: Int, protobufPayload: ByteArray) {
        val msg = ByteBuffer.allocate(2 + protobufPayload.size).order(ByteOrder.BIG_ENDIAN)
            .putShort(type.toShort())
            .put(protobufPayload)
            .array()
        callback.onSendMessage(channelId, msg)
    }

    private fun encodeVarint(value: Int): ByteArray {
        if (value < 128) return byteArrayOf(value.toByte())
        val result = mutableListOf<Byte>()
        var v = value
        while (v > 0x7F) { result.add(((v and 0x7F) or 0x80).toByte()); v = v ushr 7 }
        result.add((v and 0x7F).toByte())
        return result.toByteArray()
    }
}
