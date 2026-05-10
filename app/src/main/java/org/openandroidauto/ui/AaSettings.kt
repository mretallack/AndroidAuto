package org.openandroidauto.ui

import android.content.Context
import android.content.SharedPreferences
import org.openandroidauto.channel.VideoConfig

/**
 * Manages app settings: video config, trusted devices, connection preferences.
 */
class AaSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("aa_settings", Context.MODE_PRIVATE)

    var videoWidth: Int
        get() = prefs.getInt("video_width", 1280)
        set(v) = prefs.edit().putInt("video_width", v).apply()

    var videoHeight: Int
        get() = prefs.getInt("video_height", 720)
        set(v) = prefs.edit().putInt("video_height", v).apply()

    var videoFps: Int
        get() = prefs.getInt("video_fps", 30)
        set(v) = prefs.edit().putInt("video_fps", v).apply()

    var videoBitrate: Int
        get() = prefs.getInt("video_bitrate", 4_000_000)
        set(v) = prefs.edit().putInt("video_bitrate", v).apply()

    val videoConfig: VideoConfig
        get() = VideoConfig(videoWidth, videoHeight, videoFps, bitrate = videoBitrate)

    // Trusted devices
    fun getTrustedDevices(): Set<String> = prefs.getStringSet("trusted_devices", emptySet()) ?: emptySet()

    fun addTrustedDevice(address: String) {
        val devices = getTrustedDevices().toMutableSet()
        devices.add(address)
        prefs.edit().putStringSet("trusted_devices", devices).apply()
    }

    fun removeTrustedDevice(address: String) {
        val devices = getTrustedDevices().toMutableSet()
        devices.remove(address)
        prefs.edit().putStringSet("trusted_devices", devices).apply()
    }

    fun isTrusted(address: String): Boolean = address in getTrustedDevices()
}
