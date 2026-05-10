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
        Log.i(TAG, "Service created")
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Connecting..."))
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "Service starting")

        // Obtain MediaProjection from the activity result
        val resultCode = intent?.getIntExtra(EXTRA_PROJECTION_RESULT_CODE, -1) ?: -1
        val data = intent?.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
        if (resultCode != -1 && data != null) {
            val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
            mediaProjection = mpManager.getMediaProjection(resultCode, data)
            Log.i(TAG, "MediaProjection obtained")
        } else {
            Log.w(TAG, "No MediaProjection data in intent")
        }

        scope.launch { startSession() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "Service destroying. Frames sent=$framesSent received=$framesReceived")
        scope.cancel()
        videoChannel?.stop()
        runBlocking { transport?.disconnect() }
        wakeLock?.release()
        super.onDestroy()
    }

    private suspend fun startSession() {
        try {
            Log.i(TAG, "Looking for USB accessory...")
            val usbTransport = UsbAoaTransport(this)

            Log.i(TAG, "Connecting to USB accessory...")
            usbTransport.connect()
            Log.i(TAG, "USB connected successfully")

            Log.i(TAG, "Initializing TLS...")
            val tlsServer = AaTlsServer(AaTlsServer.getOrCreateKeyStore())
            val engine = tlsServer.createEngine()
            inBandTls = InBandTls(engine)
            Log.i(TAG, "TLS engine created (server mode, TLSv1.2)")

            transport = usbTransport

            protocolEngine = ProtocolEngine(this)
            videoChannel = VideoChannel(1u, this)
            inputChannel = InputChannel(2u, this)

            mediaProjection?.let { videoChannel?.setMediaProjection(it) }

            Log.i(TAG, "Starting protocol - sending VERSION_REQUEST")
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
        val buffer = ByteBuffer.allocate(32768) // extra space for partial frames
        val decoder = MessageFramer.Decoder()
        Log.i(TAG, "Read loop started")

        while (scope.isActive) {
            val read = transport.read(buffer)
            if (read <= 0) {
                Log.w(TAG, "Transport read returned $read, disconnecting")
                break
            }

            framesReceived++
            buffer.flip()
            val messages = decoder.decode(buffer)
            buffer.compact() // preserve any unconsumed partial frame data

            for (msg in messages) {
                if (msg.payload.size >= 2) {
                    val type = ((msg.payload[0].toInt() and 0xFF) shl 8) or (msg.payload[1].toInt() and 0xFF)
                    Log.d(TAG, "← ch=${msg.channelId} type=0x${type.toString(16)} len=${msg.payload.size}")
                }
                routeMessage(msg)
            }
        }
        Log.i(TAG, "Read loop ended")
        stopSelf()
    }

    private suspend fun writeLoop() {
        Log.d(TAG, "Write loop started")
        try {
            for (frame in writeQueue) {
                transport?.write(ByteBuffer.wrap(frame))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Write loop error: ${e.message}")
        }
        Log.d(TAG, "Write loop ended")
    }

    private fun routeMessage(msg: MessageFramer.Decoder.Message) {
        if (msg.payload.isEmpty()) return

        // Decrypt if TLS is active (check encrypted flag in frame)
        val tls = inBandTls
        val payload = if (tls != null && tls.isHandshakeComplete && msg.payload.size > 2) {
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
            Log.d(TAG, "→ ch=$channelId type=0x${type.toString(16)} len=${payload.size} ctrl=$control")
        }
        val tls = inBandTls
        val encrypted = if (tls != null && tls.isHandshakeComplete) {
            tls.encrypt(payload)
        } else payload
        val isEncrypted = tls?.isHandshakeComplete == true
        val frames = MessageFramer.encode(channelId, encrypted, control, isEncrypted)
        frames.forEach { frame ->
            writeQueue.trySend(frame)
        }
    }

    override fun onTlsData(data: ByteArray) {
        Log.d(TAG, "TLS handshake data received: ${data.size} bytes")
        val tls = inBandTls ?: return
        val responses = tls.feedHandshakeData(data)
        for (record in responses) {
            protocolEngine?.sendTlsData(record)
        }
        if (tls.isHandshakeComplete) {
            Log.i(TAG, "TLS handshake complete, notifying protocol engine")
            protocolEngine?.onTlsHandshakeComplete()
        }
    }

    override fun onTlsComplete() {
        // Handled in onTlsData when InBandTls reports handshake complete
    }

    override fun onServiceDiscoveryRequest(deviceName: String, deviceBrand: String) {
        Log.i(TAG, "Service discovery from: $deviceName ($deviceBrand)")
        val response = ServiceDiscoveryBuilder.build()
        Log.i(TAG, "Sending service discovery response: ${response.size} bytes, 4 channels")
        protocolEngine?.sendServiceDiscoveryResponse(response)
    }

    override fun onChannelOpenRequest(channelId: Int, priority: Int) {
        Log.i(TAG, "Channel open request: ch=$channelId priority=$priority")
        protocolEngine?.sendChannelOpenResponse(0)
    }

    override fun onActive() {
        Log.i(TAG, "Protocol ACTIVE - connection established!")
        updateNotification("Connected")
    }

    override fun onShutdown() {
        Log.i(TAG, "Shutdown requested")
        updateNotification("Disconnected")
        stopSelf()
    }

    // --- VideoChannelCallback ---
    override fun onVideoFrame(channelId: UByte, payload: ByteArray) {
        onSendFrame(channelId, payload, control = false)
    }

    // --- InputChannelCallback ---
    override fun onTouchEvent(event: InputEvent) {
        Log.d(TAG, "Touch: ${event.action} points=${event.touchPoints.size} " +
            "pos=(${event.touchPoints.firstOrNull()?.x},${event.touchPoints.firstOrNull()?.y})")
    }

    override fun onKeyEvent(event: KeyEvent) {
        val androidCode = KeyCodeMap.toAndroidKeyCode(event.scanCode)
        Log.d(TAG, "Key: scanCode=${event.scanCode} android=$androidCode pressed=${event.isPressed} long=${event.longPress}")
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
        Log.i(TAG, "Status: $text")
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
