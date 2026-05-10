package org.openandroidauto.transport

import android.content.Context
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer

class UsbAoaTransport(private val context: Context) : Transport {

    companion object {
        const val MODEL = "Android Auto"
        const val MODEL_ALT = "Android Open Automotive Protocol"

        fun findAccessory(context: Context): UsbAccessory? {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            return usbManager.accessoryList?.firstOrNull {
                it.model == MODEL || it.model == MODEL_ALT
            }
        }
    }

    private var fileDescriptor: ParcelFileDescriptor? = null
    private var inputStream: FileInputStream? = null
    private var outputStream: FileOutputStream? = null
    private val readBuf = ByteArray(16384)

    override var isConnected: Boolean = false
        private set

    override suspend fun connect() = withContext(Dispatchers.IO) {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val accessory = findAccessory(context)
            ?: throw IOException("No Android Auto accessory found")

        val fd = usbManager.openAccessory(accessory)
            ?: throw IOException("Failed to open accessory")

        fileDescriptor = fd
        inputStream = FileInputStream(fd.fileDescriptor)
        outputStream = FileOutputStream(fd.fileDescriptor)
        isConnected = true
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        isConnected = false
        inputStream?.close()
        outputStream?.close()
        fileDescriptor?.close()
        inputStream = null
        outputStream = null
        fileDescriptor = null
    }

    override suspend fun read(buffer: ByteBuffer): Int = withContext(Dispatchers.IO) {
        val stream = inputStream ?: throw IOException("Not connected")
        val len = minOf(buffer.remaining(), readBuf.size)
        val read = stream.read(readBuf, 0, len)
        if (read > 0) buffer.put(readBuf, 0, read)
        read
    }

    override suspend fun write(buffer: ByteBuffer) = withContext(Dispatchers.IO) {
        val stream = outputStream ?: throw IOException("Not connected")
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        stream.write(bytes)
    }
}
