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

    private var serviceStarted = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        requestScreenCapture()
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            Log.w(TAG, "Screen capture permission granted")
            startServiceWithProjection(result.resultCode, result.data!!)
        } else {
            Log.w(TAG, "Screen capture denied - starting service without projection")
            startService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.w(TAG, "onCreate action=${intent?.action}")
        handleUsbIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        Log.w(TAG, "onNewIntent action=${intent.action}")
        handleUsbIntent(intent)
    }

    private fun handleUsbIntent(intent: Intent?) {
        if (intent?.action == UsbManager.ACTION_USB_ACCESSORY_ATTACHED) {
            val accessory = intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
            Log.w(TAG, "Accessory attached: ${accessory?.manufacturer}/${accessory?.model}")
        }

        val missing = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            requestScreenCapture()
        }
    }

    private fun requestScreenCapture() {
        // Skip screen capture prompt - using test pattern for video
        startService()
    }

    private fun startServiceWithProjection(resultCode: Int, data: Intent) {
        if (serviceStarted) return
        serviceStarted = true
        val serviceIntent = Intent(this, ProjectionService::class.java).apply {
            putExtra(ProjectionService.EXTRA_PROJECTION_RESULT_CODE, resultCode)
            putExtra(ProjectionService.EXTRA_PROJECTION_DATA, data)
        }
        startForegroundService(serviceIntent)
        // Don't finish() - keep activity alive to prevent process throttling
        moveTaskToBack(true)
    }

    private fun startService() {
        if (serviceStarted) return
        serviceStarted = true
        startForegroundService(Intent(this, ProjectionService::class.java))
        moveTaskToBack(true)
    }
}
