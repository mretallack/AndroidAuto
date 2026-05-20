package org.openandroidauto.input

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import org.openandroidauto.channel.InputEvent
import org.openandroidauto.channel.TouchAction
import org.openandroidauto.channel.TouchPoint

/**
 * Injects touch events into the Android system.
 * Uses MotionEvent dispatch via the virtual display's input surface.
 *
 * Touch coordinates from the head unit (800x480) are mapped to the
 * phone's virtual display resolution.
 */
class TouchInjector(
    private val sourceWidth: Int = 800,
    private val sourceHeight: Int = 480,
    private val targetWidth: Int = 800,
    private val targetHeight: Int = 480
) {
    private val TAG = "AATouchInjector"
    private var lastDownTime = 0L
    private var pointerCount = 0

    interface Callback {
        fun injectMotionEvent(event: MotionEvent)
    }

    var callback: Callback? = null

    /**
     * Convert an AA InputEvent to Android MotionEvent and inject it.
     */
    fun inject(event: InputEvent) {
        val cb = callback ?: return
        if (event.touchPoints.isEmpty()) return

        val action = when (event.action) {
            TouchAction.PRESS -> {
                lastDownTime = SystemClock.uptimeMillis()
                pointerCount = 1
                MotionEvent.ACTION_DOWN
            }
            TouchAction.RELEASE -> {
                pointerCount = 0
                MotionEvent.ACTION_UP
            }
            TouchAction.DRAG -> MotionEvent.ACTION_MOVE
            TouchAction.POINTER_DOWN -> {
                pointerCount++
                MotionEvent.ACTION_POINTER_DOWN or (event.actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            }
            TouchAction.POINTER_UP -> {
                pointerCount--
                MotionEvent.ACTION_POINTER_UP or (event.actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            }
        }

        val now = SystemClock.uptimeMillis()
        val downTime = if (lastDownTime > 0) lastDownTime else now

        // Build pointer properties and coordinates
        val count = event.touchPoints.size
        val properties = Array(count) { i ->
            MotionEvent.PointerProperties().apply {
                id = event.touchPoints[i].pointerId
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coords = Array(count) { i ->
            val point = event.touchPoints[i]
            MotionEvent.PointerCoords().apply {
                x = (point.x.toFloat() * targetWidth / sourceWidth)
                y = (point.y.toFloat() * targetHeight / sourceHeight)
                pressure = 1.0f
                size = 1.0f
            }
        }

        val motionEvent = MotionEvent.obtain(
            downTime, now, action, count,
            properties, coords,
            0, 0, 1.0f, 1.0f,
            0, 0, 0, 0
        )

        try {
            cb.injectMotionEvent(motionEvent)
        } finally {
            motionEvent.recycle()
        }
    }

    /**
     * Create a simple single-touch MotionEvent for testing.
     */
    companion object {
        fun createTouchEvent(action: Int, x: Float, y: Float): MotionEvent {
            val now = SystemClock.uptimeMillis()
            return MotionEvent.obtain(now, now, action, x, y, 0)
        }
    }
}
