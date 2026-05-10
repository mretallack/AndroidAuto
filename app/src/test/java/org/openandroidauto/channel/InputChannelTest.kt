package org.openandroidauto.channel

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class InputChannelTest {

    private lateinit var channel: InputChannel
    private lateinit var cb: TestInputCallback

    class TestInputCallback : InputChannelCallback {
        val touchEvents = mutableListOf<InputEvent>()
        val keyEvents = mutableListOf<KeyEvent>()
        val messages = mutableListOf<Pair<UByte, ByteArray>>()

        override fun onTouchEvent(event: InputEvent) { touchEvents.add(event) }
        override fun onKeyEvent(event: KeyEvent) { keyEvents.add(event) }
        override fun onSendMessage(channelId: UByte, payload: ByteArray) {
            messages.add(channelId to payload)
        }
    }

    @Before
    fun setUp() {
        cb = TestInputCallback()
        channel = InputChannel(2u, cb, sourceWidth = 800, sourceHeight = 480, targetWidth = 1600, targetHeight = 960)
    }

    @Test
    fun `BINDING_REQUEST sends BINDING_RESPONSE with OK`() {
        channel.onMessage(InputMessageType.BINDING_REQUEST, byteArrayOf(0x0A, 0x01, 0x01))
        assertEquals(1, cb.messages.size)
        val msg = cb.messages[0].second
        val type = ByteBuffer.wrap(msg).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
        assertEquals(InputMessageType.BINDING_RESPONSE, type)
    }

    @Test
    fun `parse single touch PRESS event`() {
        val touchLocation = byteArrayOf(0x08, 0x64, 0x10, 0xC8.toByte(), 0x01, 0x18, 0x00)
        val touchEvent = byteArrayOf(0x0A, touchLocation.size.toByte()) + touchLocation + byteArrayOf(0x18, 0x00)
        val inputEvent = byteArrayOf(0x08, 0xE8.toByte(), 0x07, 0x1A, touchEvent.size.toByte()) + touchEvent

        channel.onMessage(InputMessageType.INPUT_EVENT_INDICATION, inputEvent)

        assertEquals(1, cb.touchEvents.size)
        assertEquals(100, cb.touchEvents[0].touchPoints[0].x)
        assertEquals(200, cb.touchEvents[0].touchPoints[0].y)
        assertEquals(TouchAction.PRESS, cb.touchEvents[0].action)
    }

    @Test
    fun `parse multi-touch with two pointers`() {
        // Two touch locations + POINTER_DOWN action
        val loc1 = byteArrayOf(0x08, 0x32, 0x10, 0x64, 0x18, 0x00) // x=50, y=100, id=0
        val loc2 = byteArrayOf(0x08, 0x96.toByte(), 0x01, 0x10, 0xC8.toByte(), 0x01, 0x18, 0x01) // x=150, y=200, id=1
        val touchEvent = byteArrayOf(0x0A, loc1.size.toByte()) + loc1 +
            byteArrayOf(0x0A, loc2.size.toByte()) + loc2 +
            byteArrayOf(0x10, 0x01) + // action_index = 1
            byteArrayOf(0x18, 0x05)   // touch_action = POINTER_DOWN(5)
        val inputEvent = byteArrayOf(0x08, 0x01, 0x1A, touchEvent.size.toByte()) + touchEvent

        channel.onMessage(InputMessageType.INPUT_EVENT_INDICATION, inputEvent)

        assertEquals(1, cb.touchEvents.size)
        val event = cb.touchEvents[0]
        assertEquals(2, event.touchPoints.size)
        assertEquals(0, event.touchPoints[0].pointerId)
        assertEquals(1, event.touchPoints[1].pointerId)
        assertEquals(TouchAction.POINTER_DOWN, event.action)
        assertEquals(1, event.actionIndex)
    }

    @Test
    fun `parse POINTER_UP action`() {
        val loc = byteArrayOf(0x08, 0x10, 0x10, 0x20, 0x18, 0x00)
        val touchEvent = byteArrayOf(0x0A, loc.size.toByte()) + loc + byteArrayOf(0x18, 0x06) // POINTER_UP
        val inputEvent = byteArrayOf(0x08, 0x01, 0x1A, touchEvent.size.toByte()) + touchEvent

        val parsed = InputChannel.parseInputEvent(inputEvent)
        assertNotNull(parsed)
        assertEquals(TouchAction.POINTER_UP, parsed!!.action)
    }

    @Test
    fun `coordinate mapping scales correctly`() {
        // Source: 800x480, Target: 1600x960 (2x scale)
        val point = TouchPoint(400, 240, 0)
        val mapped = channel.mapCoordinates(point)
        assertEquals(800, mapped.x)
        assertEquals(480, mapped.y)
        assertEquals(0, mapped.pointerId)
    }

    @Test
    fun `coordinate mapping handles edge values`() {
        val origin = channel.mapCoordinates(TouchPoint(0, 0, 0))
        assertEquals(0, origin.x)
        assertEquals(0, origin.y)

        val max = channel.mapCoordinates(TouchPoint(800, 480, 0))
        assertEquals(1600, max.x)
        assertEquals(960, max.y)
    }

    @Test
    fun `parse button event - key press`() {
        // InputEventIndication with button_event (field 4)
        // ButtonEvents { repeated ButtonEvent { scan_code=85, is_pressed=true } }
        val buttonEvent = byteArrayOf(0x08, 0x55, 0x10, 0x01) // scan_code=85, is_pressed=true
        val buttonEvents = byteArrayOf(0x0A, buttonEvent.size.toByte()) + buttonEvent // field 1, repeated
        val inputEvent = byteArrayOf(
            0x08, 0x01, // timestamp=1
            0x22, buttonEvents.size.toByte() // field 4 (button_event)
        ) + buttonEvents

        channel.onMessage(InputMessageType.INPUT_EVENT_INDICATION, inputEvent)

        assertEquals(1, cb.keyEvents.size)
        assertEquals(85, cb.keyEvents[0].scanCode) // MEDIA_PLAY_PAUSE
        assertTrue(cb.keyEvents[0].isPressed)
    }

    @Test
    fun `parse button event - key release with meta`() {
        val buttonEvent = byteArrayOf(0x08, 0x04, 0x10, 0x00, 0x18, 0x01, 0x20, 0x00) // BACK, released, meta=1
        val buttonEvents = byteArrayOf(0x0A, buttonEvent.size.toByte()) + buttonEvent
        val inputEvent = byteArrayOf(0x08, 0x01, 0x22, buttonEvents.size.toByte()) + buttonEvents

        channel.onMessage(InputMessageType.INPUT_EVENT_INDICATION, inputEvent)

        assertEquals(1, cb.keyEvents.size)
        assertEquals(4, cb.keyEvents[0].scanCode) // BACK
        assertFalse(cb.keyEvents[0].isPressed)
        assertEquals(1, cb.keyEvents[0].meta)
    }

    @Test
    fun `KeyCodeMap maps AA codes to Android codes`() {
        assertEquals(4, KeyCodeMap.toAndroidKeyCode(4))   // BACK
        assertEquals(3, KeyCodeMap.toAndroidKeyCode(3))   // HOME
        assertEquals(85, KeyCodeMap.toAndroidKeyCode(85)) // MEDIA_PLAY_PAUSE
        assertEquals(87, KeyCodeMap.toAndroidKeyCode(87)) // MEDIA_NEXT
        assertEquals(999, KeyCodeMap.toAndroidKeyCode(999)) // unknown → passthrough
    }

    @Test
    fun `channel ID preserved in callback`() {
        channel.onMessage(InputMessageType.BINDING_REQUEST, byteArrayOf(0x0A, 0x01, 0x01))
        assertEquals(2.toUByte(), cb.messages[0].first)
    }
}
