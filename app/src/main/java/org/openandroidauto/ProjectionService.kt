package org.openandroidauto

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel as CoChannel
import org.openandroidauto.channel.*
import org.openandroidauto.protocol.*
import org.openandroidauto.tls.AaTlsServer
import org.openandroidauto.tls.InBandTls
import org.openandroidauto.transport.Transport
import org.openandroidauto.transport.UsbAoaTransport
import java.nio.ByteBuffer

class ProjectionService : Service(), ProtocolCallback, VideoChannelCallback, InputChannelCallback {

    companion object {
        private const val TAG = "AAProjection"
        private const val CHANNEL_ID = "aa_projection"
        private const val NOTIFICATION_ID = 1
        const val EXTRA_PROJECTION_RESULT_CODE = "projection_result_code"
        const val EXTRA_PROJECTION_DATA = "projection_data"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val writeQueue = CoChannel<ByteArray>(capacity = 64)
    private var transport: Transport? = null
    private var protocolEngine: ProtocolEngine? = null
    private var inBandTls: InBandTls? = null
    private var videoChannel: VideoChannel? = null
    private var inputChannel: InputChannel? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var mediaProjection: MediaProjection? = null
    private var framesSent = 0L
    private var framesReceived = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.w(TAG, "Service created")
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Connecting..."))
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.w(TAG, "Service starting")
        scope.launch { startSession() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.w(TAG, "Service destroying. Frames sent=$framesSent received=$framesReceived")
        scope.cancel()
        videoChannel?.stop()
        runBlocking { transport?.disconnect() }
        wakeLock?.release()
        super.onDestroy()
    }

    private suspend fun startSession() {
        try {
            Log.w(TAG, "Looking for USB accessory...")
            val usbTransport = UsbAoaTransport(this)

            Log.w(TAG, "Connecting to USB accessory...")
            usbTransport.connect()
            Log.w(TAG, "USB connected successfully")

            Log.w(TAG, "Initializing TLS...")
            val tlsServer = AaTlsServer(AaTlsServer.createKeyStore(this@ProjectionService))
            val engine = tlsServer.createEngine()
            inBandTls = InBandTls(engine)
            Log.w(TAG, "TLS engine created (server mode, TLSv1.2)")

            transport = usbTransport

            protocolEngine = ProtocolEngine(this)
            videoChannel = VideoChannel(1u, this)
            inputChannel = InputChannel(2u, this)

            mediaProjection?.let { videoChannel?.setMediaProjection(it) }

            Log.w(TAG, "Starting protocol - waiting for head unit VERSION_REQUEST")
            protocolEngine?.start()

            // Start single writer coroutine to serialize all frame writes
            scope.launch { writeLoop() }

            readLoop(usbTransport)
        } catch (e: Exception) {
            Log.e(TAG, "Session failed: ${e.message}", e)
            updateNotification("Error: ${e.message}")
            stopSelf()
        }
    }

    private suspend fun readLoop(transport: Transport) {
        val buffer = ByteBuffer.allocate(32768)
        val decoder = MessageFramer.Decoder()
        Log.w(TAG, "Read loop started")

        while (scope.isActive) {
            val read = transport.read(buffer)
            if (read <= 0) {
                Log.w(TAG, "Transport read returned $read, disconnecting")
                break
            }

            framesReceived++
            // Log raw bytes for debugging
            val pos = buffer.position()
            val rawHex = StringBuilder()
            for (i in 0 until minOf(pos, 32)) { rawHex.append(String.format("%02x ", buffer[i])) }
            Log.w(TAG, "RAW ← $pos bytes: $rawHex")

            buffer.flip()
            val messages = decoder.decode(buffer)
            buffer.compact()

            for (msg in messages) {
                if (msg.payload.size >= 2) {
                    val type = ((msg.payload[0].toInt() and 0xFF) shl 8) or (msg.payload[1].toInt() and 0xFF)
                    Log.w(TAG, "← ch=${msg.channelId} type=0x${type.toString(16)} len=${msg.payload.size}")
                }
                routeMessage(msg)
            }
        }
        Log.w(TAG, "Read loop ended")
        stopSelf()
    }

    private suspend fun writeLoop() {
        Log.w(TAG, "Write loop started")
        try {
            for (frame in writeQueue) {
                val hex = StringBuilder()
                for (i in 0 until minOf(frame.size, 32)) { hex.append(String.format("%02x ", frame[i])) }
                Log.w(TAG, "RAW → ${frame.size} bytes: $hex")
                transport?.write(ByteBuffer.wrap(frame))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Write loop error: ${e.message}")
        }
        Log.w(TAG, "Write loop ended")
    }

    private fun routeMessage(msg: MessageFramer.Decoder.Message) {
        if (msg.payload.isEmpty()) return

        val tls = inBandTls
        val engineState = protocolEngine?.state

        // During TLS handshake, any data on channel 0 might be TLS records
        if (engineState == ProtocolState.TLS_HANDSHAKE && msg.channelId.toInt() == 0 && tls != null && !tls.isHandshakeComplete) {
            // Check if this looks like a message type we know, or raw TLS data
            val possibleType = if (msg.payload.size >= 2) ((msg.payload[0].toInt() and 0xFF) shl 8) or (msg.payload[1].toInt() and 0xFF) else 0
            if (possibleType == ControlMessageType.SSL_HANDSHAKE) {
                // Standard SSL_HANDSHAKE message
                val tlsData = if (msg.payload.size > 2) msg.payload.copyOfRange(2, msg.payload.size) else ByteArray(0)
                onTlsData(tlsData)
            } else {
                // Could be encrypted TLS flight - feed entire payload to TLS engine
                Log.w(TAG, "Feeding ${msg.payload.size} bytes to TLS (possible encrypted flight)")
                onTlsData(msg.payload)
            }
            return
        }

        // Decrypt only if frame has encrypted flag set (bit 3 of flags)
        val isEncryptedFrame = (msg.flags.toInt() and 0x08) != 0
        val payload = if (tls != null && tls.isHandshakeComplete && isEncryptedFrame && msg.payload.size > 2) {
            tls.decrypt(msg.payload)
        } else msg.payload

        if (payload.size < 2) return
        val type = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
        val msgPayload = if (payload.size > 2) payload.copyOfRange(2, payload.size) else ByteArray(0)

        when (msg.channelId.toInt()) {
            0 -> protocolEngine?.onMessage(type, msgPayload)
            1 -> videoChannel?.onMessage(type, msgPayload)
            2 -> inputChannel?.onMessage(type, msgPayload)
            else -> Log.w(TAG, "Unknown channel ${msg.channelId} type=0x${type.toString(16)}")
        }
    }

    // --- ProtocolCallback ---
    override fun onSendFrame(channelId: UByte, payload: ByteArray, control: Boolean) {
        framesSent++
        if (payload.size >= 2) {
            val type = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
            Log.w(TAG, "→ ch=$channelId type=0x${type.toString(16)} len=${payload.size} ctrl=$control")
        }
        // Don't encrypt for now - head unit sends all frames unencrypted
        val frames = MessageFramer.encode(channelId, payload, control, encrypted = false)
        frames.forEach { frame ->
            writeQueue.trySend(frame)
        }
    }

    override fun onTlsData(data: ByteArray) {
        Log.w(TAG, "TLS handshake data received: ${data.size} bytes")
        val tls = inBandTls ?: return

        // Begin handshake on first TLS data received
        if (!tls.isHandshakeComplete) {
            val initialResponses = tls.beginHandshake()
            for (record in initialResponses) {
                Log.w(TAG, "TLS → sending ${record.size} bytes (initial)")
                protocolEngine?.sendTlsData(record)
            }
        }

        val responses = tls.feedHandshakeData(data)
        for (record in responses) {
            Log.w(TAG, "TLS → sending ${record.size} bytes")
            protocolEngine?.sendTlsData(record)
        }
        if (tls.isHandshakeComplete) {
            Log.w(TAG, "TLS handshake complete, notifying protocol engine")
            protocolEngine?.onTlsHandshakeComplete()
        }
    }

    override fun onTlsComplete() {
        // Handled in onTlsData when InBandTls reports handshake complete
    }

    override fun onServiceDiscoveryRequest(deviceName: String, deviceBrand: String) {
        Log.w(TAG, "Service discovery from: $deviceName ($deviceBrand)")
        val response = ServiceDiscoveryBuilder.build()
        Log.w(TAG, "Sending service discovery response: ${response.size} bytes, 4 channels")
        protocolEngine?.sendServiceDiscoveryResponse(response)
    }

    override fun onChannelOpenRequest(channelId: Int, priority: Int) {
        Log.w(TAG, "Channel open request: ch=$channelId priority=$priority")
        protocolEngine?.sendChannelOpenResponse(0)
    }

    override fun onActive() {
        Log.w(TAG, "Protocol ACTIVE - connection established!")
        updateNotification("Connected")
    }

    override fun onShutdown() {
        Log.w(TAG, "Shutdown requested")
        updateNotification("Disconnected")
        stopSelf()
    }

    // --- VideoChannelCallback ---
    override fun onVideoFrame(channelId: UByte, payload: ByteArray) {
        onSendFrame(channelId, payload, control = false)
    }

    // --- InputChannelCallback ---
    override fun onTouchEvent(event: InputEvent) {
        Log.w(TAG, "Touch: ${event.action} points=${event.touchPoints.size} " +
            "pos=(${event.touchPoints.firstOrNull()?.x},${event.touchPoints.firstOrNull()?.y})")
    }

    override fun onKeyEvent(event: KeyEvent) {
        val androidCode = KeyCodeMap.toAndroidKeyCode(event.scanCode)
        Log.w(TAG, "Key: scanCode=${event.scanCode} android=$androidCode pressed=${event.isPressed} long=${event.longPress}")
    }

    override fun onSendMessage(channelId: UByte, payload: ByteArray) {
        onSendFrame(channelId, payload, control = false)
    }

    // --- Notification ---
    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Android Auto", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Open Android Auto")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        Log.w(TAG, "Status: $text")
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OpenAA::Projection").apply {
            acquire(4 * 60 * 60 * 1000L)
        }
    }
}
