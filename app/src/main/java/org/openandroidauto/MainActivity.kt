package org.openandroidauto

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
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
    ) { _ ->
        startServiceIfAccessory()
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
            startServiceIfAccessory()
        }
    }

    private fun startServiceIfAccessory() {
        val accessory = UsbAoaTransport.findAccessory(this)
        if (accessory != null) {
            Log.w(TAG, "Starting ProjectionService for: ${accessory.manufacturer}/${accessory.model}")
            startForegroundService(Intent(this, ProjectionService::class.java))
            finish()
        } else {
            Log.w(TAG, "No USB accessory found")
        }
    }
}
