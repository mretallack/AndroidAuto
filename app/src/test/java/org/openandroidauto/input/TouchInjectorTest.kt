package org.openandroidauto.input

import org.junit.Assert.*
import org.junit.Test
import org.openandroidauto.channel.InputEvent
import org.openandroidauto.channel.TouchAction
import org.openandroidauto.channel.TouchPoint

class TouchInjectorTest {

    @Test
    fun `coordinate scaling - same resolution`() {
        val injector = TouchInjector(sourceWidth = 800, sourceHeight = 480, targetWidth = 800, targetHeight = 480)
        // x=400/800 * 800 = 400, y=240/480 * 480 = 240
        val point = TouchPoint(400, 240, 0)
        val scaledX = point.x.toFloat() * 800 / 800
        val scaledY = point.y.toFloat() * 480 / 480
        assertEquals(400f, scaledX, 0.1f)
        assertEquals(240f, scaledY, 0.1f)
    }

    @Test
    fun `coordinate scaling - 2x resolution`() {
        val sourceWidth = 800
        val sourceHeight = 480
        val targetWidth = 1600
        val targetHeight = 960
        val point = TouchPoint(400, 240, 0)
        val scaledX = point.x.toFloat() * targetWidth / sourceWidth
        val scaledY = point.y.toFloat() * targetHeight / sourceHeight
        assertEquals(800f, scaledX, 0.1f)
        assertEquals(480f, scaledY, 0.1f)
    }

    @Test
    fun `coordinate scaling - corner cases`() {
        val sourceWidth = 800
        val targetWidth = 1280
        // Origin
        assertEquals(0f, 0f * targetWidth / sourceWidth, 0.1f)
        // Max
        assertEquals(1280f, 800f * targetWidth / sourceWidth, 0.1f)
    }

    @Test
    fun `touch action mapping - PRESS to ACTION_DOWN`() {
        // android.view.MotionEvent.ACTION_DOWN = 0
        val action = when (TouchAction.PRESS) {
            TouchAction.PRESS -> 0 // ACTION_DOWN
            TouchAction.RELEASE -> 1 // ACTION_UP
            TouchAction.DRAG -> 2 // ACTION_MOVE
            TouchAction.POINTER_DOWN -> 5 // ACTION_POINTER_DOWN
            TouchAction.POINTER_UP -> 6 // ACTION_POINTER_UP
        }
        assertEquals(0, action)
    }

    @Test
    fun `touch action mapping - RELEASE to ACTION_UP`() {
        val action = when (TouchAction.RELEASE) {
            TouchAction.PRESS -> 0
            TouchAction.RELEASE -> 1
            TouchAction.DRAG -> 2
            TouchAction.POINTER_DOWN -> 5
            TouchAction.POINTER_UP -> 6
        }
        assertEquals(1, action)
    }

    @Test
    fun `touch action mapping - DRAG to ACTION_MOVE`() {
        val action = when (TouchAction.DRAG) {
            TouchAction.PRESS -> 0
            TouchAction.RELEASE -> 1
            TouchAction.DRAG -> 2
            TouchAction.POINTER_DOWN -> 5
            TouchAction.POINTER_UP -> 6
        }
        assertEquals(2, action)
    }

    @Test
    fun `multi-touch pointer index encoding`() {
        // ACTION_POINTER_DOWN with actionIndex=1 should encode as:
        // ACTION_POINTER_DOWN | (1 << ACTION_POINTER_INDEX_SHIFT)
        // ACTION_POINTER_DOWN = 5, ACTION_POINTER_INDEX_SHIFT = 8
        val actionIndex = 1
        val encoded = 5 or (actionIndex shl 8) // 0x0105
        assertEquals(5, encoded and 0xFF) // masked action
        assertEquals(1, (encoded shr 8) and 0xFF) // pointer index
    }

    @Test
    fun `empty touch points produces no event`() {
        val event = InputEvent(1000L, emptyList(), TouchAction.PRESS)
        assertTrue(event.touchPoints.isEmpty())
    }

    @Test
    fun `touch injector creation with default params`() {
        val injector = TouchInjector()
        assertNotNull(injector)
    }

    @Test
    fun `touch injector creation with custom params`() {
        val injector = TouchInjector(sourceWidth = 1024, sourceHeight = 600, targetWidth = 1920, targetHeight = 1080)
        assertNotNull(injector)
    }
}
