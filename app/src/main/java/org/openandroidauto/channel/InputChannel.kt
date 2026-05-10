package org.openandroidauto.channel

import java.nio.ByteBuffer
import java.nio.ByteOrder

object InputMessageType {
    const val INPUT_EVENT_INDICATION: Int = 0x8001
    const val BINDING_REQUEST: Int = 0x8002
    const val BINDING_RESPONSE: Int = 0x8003
}

data class TouchPoint(val x: Int, val y: Int, val pointerId: Int)

enum class TouchAction(val value: Int) {
    PRESS(0), RELEASE(1), DRAG(2), POINTER_DOWN(5), POINTER_UP(6);
    companion object {
        fun from(v: Int) = entries.firstOrNull { it.value == v } ?: PRESS
    }
}

data class InputEvent(
    val timestamp: Long,
    val touchPoints: List<TouchPoint>,
    val action: TouchAction,
    val actionIndex: Int = 0
)

data class KeyEvent(
    val scanCode: Int,
    val isPressed: Boolean,
    val meta: Int = 0,
    val longPress: Boolean = false
)

/**
 * Maps AA keycodes to Android KeyEvent codes.
 */
object KeyCodeMap {
    // AA scan codes → Android KeyEvent.KEYCODE_*
    private val map = mapOf(
        1 to 4,    // SOFT_LEFT → BACK
        2 to 3,    // SOFT_RIGHT → HOME
        3 to 3,    // HOME → HOME
        4 to 4,    // BACK → BACK
        5 to 5,    // CALL → CALL
        6 to 6,    // END_CALL → ENDCALL
        19 to 19,  // DPAD_UP
        20 to 20,  // DPAD_DOWN
        21 to 21,  // DPAD_LEFT
        22 to 22,  // DPAD_RIGHT
        23 to 23,  // DPAD_CENTER → ENTER
        24 to 24,  // VOLUME_UP
        25 to 25,  // VOLUME_DOWN
        79 to 79,  // HEADSETHOOK
        85 to 85,  // MEDIA_PLAY_PAUSE
        86 to 86,  // MEDIA_STOP
        87 to 87,  // MEDIA_NEXT
        88 to 88,  // MEDIA_PREVIOUS
        126 to 126, // MEDIA_PLAY
        127 to 127, // MEDIA_PAUSE
        84 to 84,  // SEARCH
    )

    fun toAndroidKeyCode(aaScanCode: Int): Int = map[aaScanCode] ?: aaScanCode
}

interface InputChannelCallback {
    fun onTouchEvent(event: InputEvent)
    fun onKeyEvent(event: KeyEvent)
    fun onSendMessage(channelId: UByte, payload: ByteArray)
}

/**
 * Handles input events from the head unit (touch + buttons).
 * Supports multi-touch with coordinate mapping.
 */
class InputChannel(
    private val channelId: UByte,
    private val callback: InputChannelCallback,
    private val sourceWidth: Int = 800,
    private val sourceHeight: Int = 480,
    private val targetWidth: Int = 1280,
    private val targetHeight: Int = 720
) {
    fun onMessage(messageType: Int, payload: ByteArray) {
        when (messageType) {
            InputMessageType.INPUT_EVENT_INDICATION -> handleInputEvent(payload)
            InputMessageType.BINDING_REQUEST -> handleBindingRequest(payload)
        }
    }

    private fun handleInputEvent(payload: ByteArray) {
        val parsed = parseInputEventIndication(payload)
        if (parsed.first != null) {
            callback.onTouchEvent(parsed.first!!)
        }
        parsed.second?.forEach { callback.onKeyEvent(it) }
    }

    private fun handleBindingRequest(payload: ByteArray) {
        val response = byteArrayOf(0x08, 0x00) // status = OK
        sendMessage(InputMessageType.BINDING_RESPONSE, response)
    }

    private fun sendMessage(type: Int, protobufPayload: ByteArray) {
        val msg = ByteBuffer.allocate(2 + protobufPayload.size).order(ByteOrder.BIG_ENDIAN)
            .putShort(type.toShort())
            .put(protobufPayload)
            .array()
        callback.onSendMessage(channelId, msg)
    }

    /**
     * Map touch coordinates from head unit resolution to phone resolution.
     */
    fun mapCoordinates(point: TouchPoint): TouchPoint {
        val mappedX = (point.x.toLong() * targetWidth / sourceWidth).toInt()
        val mappedY = (point.y.toLong() * targetHeight / sourceHeight).toInt()
        return TouchPoint(mappedX, mappedY, point.pointerId)
    }

    companion object {
        /**
         * Parse InputEventIndication protobuf → (TouchEvent?, List<KeyEvent>)
         */
        fun parseInputEventIndication(data: ByteArray): Pair<InputEvent?, List<KeyEvent>> {
            var timestamp = 0L
            var touchEvent: InputEvent? = null
            val keyEvents = mutableListOf<KeyEvent>()

            var i = 0
            while (i < data.size) {
                val tag = data[i].toInt() and 0xFF
                val field = tag ushr 3
                val wireType = tag and 0x07
                i++

                when (wireType) {
                    0 -> { // varint
                        val (value, newI) = readVarint(data, i)
                        i = newI
                        if (field == 1) timestamp = value
                    }
                    2 -> { // length-delimited
                        val (len, newI) = readVarint(data, i).let { it.first.toInt() to it.second }
                        i = newI
                        if (i + len > data.size) break
                        val subData = data.copyOfRange(i, i + len)
                        when (field) {
                            3 -> touchEvent = parseTouchEvent(subData, timestamp)
                            4 -> keyEvents.addAll(parseButtonEvents(subData))
                        }
                        i += len
                    }
                    else -> break
                }
            }
            return touchEvent to keyEvents
        }

        fun parseInputEvent(data: ByteArray): InputEvent? = parseInputEventIndication(data).first

        private fun parseTouchEvent(data: ByteArray, timestamp: Long): InputEvent? {
            val points = mutableListOf<TouchPoint>()
            var action = TouchAction.PRESS
            var actionIndex = 0
            var i = 0
            while (i < data.size) {
                val tag = data[i].toInt() and 0xFF
                val field = tag ushr 3
                val wireType = tag and 0x07
                i++
                when (wireType) {
                    0 -> {
                        val (value, newI) = readVarint(data, i)
                        i = newI
                        when (field) {
                            2 -> actionIndex = value.toInt()
                            3 -> action = TouchAction.from(value.toInt())
                        }
                    }
                    2 -> {
                        val (len, newI) = readVarint(data, i).let { it.first.toInt() to it.second }
                        i = newI
                        if (field == 1 && i + len <= data.size) {
                            parseTouchLocation(data.copyOfRange(i, i + len))?.let { points.add(it) }
                        }
                        i += len
                    }
                    else -> break
                }
            }
            if (points.isEmpty()) return null
            return InputEvent(timestamp, points, action, actionIndex)
        }

        private fun parseTouchLocation(data: ByteArray): TouchPoint? {
            var x = 0; var y = 0; var pointerId = 0
            var i = 0
            while (i < data.size) {
                val tag = data[i].toInt() and 0xFF
                val field = tag ushr 3
                i++
                val (value, newI) = readVarint(data, i)
                i = newI
                when (field) { 1 -> x = value.toInt(); 2 -> y = value.toInt(); 3 -> pointerId = value.toInt() }
            }
            return TouchPoint(x, y, pointerId)
        }

        /**
         * Parse ButtonEvents submessage → list of KeyEvents.
         * ButtonEvents { repeated ButtonEvent button_events = 1; }
         * ButtonEvent { scan_code=1, is_pressed=2, meta=3, long_press=4 }
         */
        private fun parseButtonEvents(data: ByteArray): List<KeyEvent> {
            val events = mutableListOf<KeyEvent>()
            var i = 0
            while (i < data.size) {
                val tag = data[i].toInt() and 0xFF
                val field = tag ushr 3
                val wireType = tag and 0x07
                i++
                if (wireType == 2 && field == 1) {
                    val (len, newI) = readVarint(data, i).let { it.first.toInt() to it.second }
                    i = newI
                    if (i + len <= data.size) {
                        parseButtonEvent(data.copyOfRange(i, i + len))?.let { events.add(it) }
                    }
                    i += len
                } else break
            }
            return events
        }

        private fun parseButtonEvent(data: ByteArray): KeyEvent? {
            var scanCode = 0; var isPressed = false; var meta = 0; var longPress = false
            var i = 0
            while (i < data.size) {
                val tag = data[i].toInt() and 0xFF
                val field = tag ushr 3
                i++
                val (value, newI) = readVarint(data, i)
                i = newI
                when (field) {
                    1 -> scanCode = value.toInt()
                    2 -> isPressed = value != 0L
                    3 -> meta = value.toInt()
                    4 -> longPress = value != 0L
                }
            }
            return KeyEvent(scanCode, isPressed, meta, longPress)
        }

        private fun readVarint(data: ByteArray, start: Int): Pair<Long, Int> {
            var value = 0L; var shift = 0; var i = start
            while (i < data.size) {
                val b = data[i].toInt() and 0xFF; i++
                value = value or ((b.toLong() and 0x7F) shl shift)
                if (b and 0x80 == 0) break
                shift += 7
            }
            return value to i
        }
    }
}
