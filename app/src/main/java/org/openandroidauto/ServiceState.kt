package org.openandroidauto

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Shared state between ProjectionService and the UI.
 * Singleton so both can access without binding.
 */
object ServiceState {
    enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, STREAMING, ERROR }

    val connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val framesSent = MutableStateFlow(0L)
    val framesReceived = MutableStateFlow(0L)
    val lastError = MutableStateFlow("")
    val events = MutableStateFlow<List<String>>(emptyList())

    // Settings (read by service)
    val audioEnabled = MutableStateFlow(true)
    val sensorEnabled = MutableStateFlow(true)
    val fragmentEnabled = MutableStateFlow(false)
    val testPatternEnabled = MutableStateFlow(true)

    fun addEvent(msg: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        val list = events.value.takeLast(29) + "$time $msg"
        events.value = list
    }

    fun reset() {
        connectionState.value = ConnectionState.DISCONNECTED
        framesSent.value = 0
        framesReceived.value = 0
        lastError.value = ""
        events.value = emptyList()
    }
}
