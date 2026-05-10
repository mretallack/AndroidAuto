package org.openandroidauto

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.openandroidauto.transport.UsbAoaTransport

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "OpenAndroidAuto"
        val REQUIRED_PERMISSIONS = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val denied = results.filter { !it.value }.keys
        if (denied.isNotEmpty()) {
            Log.w(TAG, "Permissions denied: $denied (continuing anyway)")
        }
        // Continue regardless — non-critical permissions can be denied
        handleConnection()
    }

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            Log.i(TAG, "MediaProjection permission granted")
            val serviceIntent = Intent(this, ProjectionService::class.java).apply {
                putExtra(ProjectionService.EXTRA_PROJECTION_RESULT_CODE, result.resultCode)
                putExtra(ProjectionService.EXTRA_PROJECTION_DATA, result.data)
            }
            startForegroundService(serviceIntent)
        } else {
            Log.w(TAG, "MediaProjection permission denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "MainActivity created")
        checkPermissionsAndStart()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.i(TAG, "onNewIntent: ${intent.action}")
        if (intent.action == UsbManager.ACTION_USB_ACCESSORY_ATTACHED) {
            checkPermissionsAndStart()
        }
    }

    private fun checkPermissionsAndStart() {
        val missing = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            Log.i(TAG, "Requesting permissions: $missing")
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            Log.i(TAG, "All permissions already granted")
            handleConnection()
        }
    }

    private fun handleConnection() {
        val accessory = UsbAoaTransport.findAccessory(this)
        if (accessory != null) {
            Log.i(TAG, "AA accessory found: ${accessory.manufacturer}/${accessory.model}")
            requestMediaProjection()
        } else {
            Log.i(TAG, "No head unit connected. Waiting for USB...")
            // When USB is plugged in, the intent filter will re-launch this activity
        }
    }

    private fun requestMediaProjection() {
        val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        Log.i(TAG, "Requesting MediaProjection consent")
        mediaProjectionLauncher.launch(mpManager.createScreenCaptureIntent())
    }
}
