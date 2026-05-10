package org.openandroidauto.navigation

import org.junit.Assert.*
import org.junit.Test
import org.openandroidauto.channel.VideoChannelCallback
import java.nio.ByteBuffer
import java.nio.ByteOrder

class NavigationChannelTest {

    class TestCallback : VideoChannelCallback {
        val frames = mutableListOf<Pair<UByte, ByteArray>>()
        val messages = mutableListOf<Pair<UByte, ByteArray>>()
        override fun onVideoFrame(channelId: UByte, payload: ByteArray) { frames.add(channelId to payload) }
        override fun onSendMessage(channelId: UByte, payload: ByteArray) { messages.add(channelId to payload) }
    }

    @Test
    fun `sendStatus ACTIVE sends correct message`() {
        val cb = TestCallback()
        val nav = NavigationChannel(5u, cb)
        nav.sendStatus(true)

        assertEquals(1, cb.messages.size)
        val msg = cb.messages[0].second
        val type = ByteBuffer.wrap(msg).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
        assertEquals(NavMessageType.STATUS, type)
        // Payload: field 1 = ACTIVE(1)
        assertEquals(0x08.toByte(), msg[2])
        assertEquals(0x01.toByte(), msg[3])
    }

    @Test
    fun `sendTurnEvent only sends when active`() {
        val cb = TestCallback()
        val nav = NavigationChannel(5u, cb)

        // Not active - should not send
        nav.sendTurnEvent(NavigationTurn("Main St", 2, 4))
        assertEquals(0, cb.messages.size)

        // Activate then send
        nav.sendStatus(true)
        cb.messages.clear()
        nav.sendTurnEvent(NavigationTurn("Main St", 2, 4))
        assertEquals(1, cb.messages.size)

        val msg = cb.messages[0].second
        val type = ByteBuffer.wrap(msg).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
        assertEquals(NavMessageType.TURN_EVENT, type)
    }

    @Test
    fun `sendDistanceEvent contains meters and unit`() {
        val cb = TestCallback()
        val nav = NavigationChannel(5u, cb)
        nav.sendStatus(true)
        cb.messages.clear()

        nav.sendDistanceEvent(NavigationDistance(500, 30, 500000, 1))
        assertEquals(1, cb.messages.size)

        val msg = cb.messages[0].second
        val type = ByteBuffer.wrap(msg).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
        assertEquals(NavMessageType.DISTANCE_EVENT, type)
    }

    @Test
    fun `channel ID preserved`() {
        val cb = TestCallback()
        val nav = NavigationChannel(9u, cb)
        nav.sendStatus(false)
        assertEquals(9.toUByte(), cb.messages[0].first)
    }
}
