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
        requestMediaProjection()
    }

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            Log.i(TAG, "MediaProjection permission granted, starting service")
            startProjectionService(result.resultCode, result.data!!)
        } else {
            Log.w(TAG, "MediaProjection permission denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "MainActivity created, action=${intent?.action}")

        if (intent?.action == UsbManager.ACTION_USB_ACCESSORY_ATTACHED) {
            Log.i(TAG, "Launched via USB accessory intent")
            val accessory = intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
            Log.i(TAG, "Accessory: manufacturer=${accessory?.manufacturer} model=${accessory?.model}")
        }

        checkPermissionsAndStart()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.i(TAG, "onNewIntent: ${intent.action}")
        setIntent(intent)
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
            requestMediaProjection()
        }
    }

    private fun requestMediaProjection() {
        // Check if accessory is available
        val accessory = UsbAoaTransport.findAccessory(this)
        if (accessory != null) {
            Log.i(TAG, "Accessory found: ${accessory.manufacturer}/${accessory.model}, requesting screen capture")
            val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjectionLauncher.launch(mpManager.createScreenCaptureIntent())
        } else {
            Log.i(TAG, "No accessory found yet. Waiting for USB connection...")
        }
    }

    private fun startProjectionService(resultCode: Int, data: Intent) {
        val serviceIntent = Intent(this, ProjectionService::class.java).apply {
            putExtra(ProjectionService.EXTRA_PROJECTION_RESULT_CODE, resultCode)
            putExtra(ProjectionService.EXTRA_PROJECTION_DATA, data)
        }
        startForegroundService(serviceIntent)
        Log.i(TAG, "ProjectionService started")
    }
}
