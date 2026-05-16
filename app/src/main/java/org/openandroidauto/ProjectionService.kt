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

class ProjectionService : Service(), ProtocolCallback, VideoChannelCallback, InputChannelCallback, SensorChannelCallback {

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
    private var wakeLock: PowerManager.WakeLock? = null
    private var mediaProjection: MediaProjection? = null
    private var framesSent = 0L
    private var framesReceived = 0L

    // Channel handlers - keyed by channel ID from service discovery
    private var videoChannel: VideoChannel? = null
    private var inputChannel: InputChannel? = null
    private var audioOutputChannel: AudioOutputChannel? = null
    private var audioInputChannel: AudioInputChannel? = null
    private var sensorChannel: SensorChannel? = null

    // Channel ID mapping from service discovery
    private var videoChannelId: Int = -1
    private var inputChannelId: Int = -1
    private var audioChannelId: Int = -1
    private var sensorChannelId: Int = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.w(TAG, "Service created")
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Connecting..."),
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.w(TAG, "Service starting")
        // Extract MediaProjection if provided
        // Note: Activity.RESULT_OK = -1, so we use a different sentinel
        val resultCode = intent?.getIntExtra(EXTRA_PROJECTION_RESULT_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val projectionData: Intent? = intent?.getParcelableExtra(EXTRA_PROJECTION_DATA)
        if (resultCode != 0 && projectionData != null) {
            val projectionManager = getSystemService(android.media.projection.MediaProjectionManager::class.java)
            mediaProjection = projectionManager.getMediaProjection(resultCode, projectionData)
            Log.w(TAG, "MediaProjection obtained successfully")
        } else {
            Log.w(TAG, "No MediaProjection - will send black frames")
        }
        scope.launch { startSession() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.w(TAG, "Service destroying. Frames sent=$framesSent received=$framesReceived")
        scope.cancel()
        videoChannel?.stop()
        audioOutputChannel?.stop()
        audioInputChannel?.stop()
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

            Log.w(TAG, "Starting protocol - waiting for head unit VERSION_REQUEST")
            protocolEngine?.start()

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
            val pos = buffer.position()
            val rawHex = StringBuilder()
            for (i in 0 until minOf(pos, 32)) { rawHex.append(String.format("%02x ", buffer[i])) }
            Log.w(TAG, "RAW ← $pos bytes: $rawHex")

            buffer.flip()
            val messages = decoder.decode(buffer)
            buffer.compact()

            for (msg in messages) {
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
            val possibleType = if (msg.payload.size >= 2) ((msg.payload[0].toInt() and 0xFF) shl 8) or (msg.payload[1].toInt() and 0xFF) else 0
            if (possibleType == ControlMessageType.SSL_HANDSHAKE) {
                val tlsData = if (msg.payload.size > 2) msg.payload.copyOfRange(2, msg.payload.size) else ByteArray(0)
                onTlsData(tlsData)
            } else {
                Log.w(TAG, "Feeding ${msg.payload.size} bytes to TLS (possible encrypted flight)")
                onTlsData(msg.payload)
            }
            return
        }

        // Decrypt if frame has encrypted flag OR if payload starts with TLS record header (0x17 0x03)
        val isEncryptedFrame = (msg.flags.toInt() and 0x08) != 0
        val isTlsRecord = msg.payload.size > 5 && msg.payload[0].toInt() and 0xFF == 0x17 && msg.payload[1].toInt() and 0xFF == 0x03
        val payload = if (tls != null && tls.isHandshakeComplete && (isEncryptedFrame || isTlsRecord)) {
            val decrypted = tls.decrypt(msg.payload)
            if (decrypted.size <= 10) {
                val preHex = msg.payload.take(20).joinToString(" ") { String.format("%02x", it) }
                val postHex = decrypted.joinToString(" ") { String.format("%02x", it) }
                Log.w(TAG, "  decrypt: ${msg.payload.size}→${decrypted.size} pre=[$preHex] post=[$postHex]")
            }
            decrypted
        } else msg.payload

        if (payload.size < 2) return
        val type = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
        val msgPayload = if (payload.size > 2) payload.copyOfRange(2, payload.size) else ByteArray(0)

        val chId = msg.channelId.toInt()
        val isControl = (msg.flags.toInt() and 0x04) != 0

        // Control-flagged messages (CHANNEL_OPEN_RESPONSE etc) on any channel
        // should be handled by the protocol engine
        if (isControl && type == ControlMessageType.CHANNEL_OPEN_RESPONSE) {
            Log.w(TAG, "← ch=$chId CHANNEL_OPEN_RESPONSE status=OK")
            protocolEngine?.onMessage(type, msgPayload)
            return
        }

        Log.w(TAG, "← ch=$chId type=0x${type.toString(16)} len=${payload.size} enc=${isEncryptedFrame || isTlsRecord} ctrl=$isControl")

        // Log hex for short/unknown messages for debugging
        if (payload.size <= 10) {
            val hex = payload.joinToString(" ") { String.format("%02x", it) }
            Log.w(TAG, "  payload hex: $hex")
        }

        // 0x00FF - unknown message from head unit. Could be:
        // - A NACK/unsupported indicator
        // - A message we're decrypting incorrectly
        // Try interpreting the raw TLS record differently
        if (chId == 0 && payload.size == 2 && type == 0x00FF) {
            Log.w(TAG, "  Unknown 0x00FF message - may indicate protocol mismatch")
            // Don't route to protocol engine - just log
            return
        }

        // Route to appropriate handler
        when (chId) {
            0 -> protocolEngine?.onMessage(type, msgPayload)
            videoChannelId -> videoChannel?.onMessage(type, msgPayload)
            inputChannelId -> inputChannel?.onMessage(type, msgPayload)
            audioChannelId -> audioOutputChannel?.onMessage(type, msgPayload)
            sensorChannelId -> sensorChannel?.onMessage(type, msgPayload)
            else -> {
                // Try matching by channel type if IDs not yet assigned
                if (chId == 1) videoChannel?.onMessage(type, msgPayload)
                else if (chId == 2) inputChannel?.onMessage(type, msgPayload)
                else Log.w(TAG, "Unrouted message ch=$chId type=0x${type.toString(16)}")
            }
        }
    }

    // --- ProtocolCallback ---

    override fun onSendFrame(channelId: UByte, payload: ByteArray, control: Boolean) {
        framesSent++
        if (payload.size >= 2) {
            val type = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
            Log.w(TAG, "→ ch=$channelId type=0x${type.toString(16)} len=${payload.size} ctrl=$control")
        }
        val tls = inBandTls
        val shouldEncrypt = tls != null && tls.isHandshakeComplete &&
            protocolEngine?.state != ProtocolState.TLS_HANDSHAKE
        val type = if (payload.size >= 2) ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF) else 0
        val isPlainMessage = type == ControlMessageType.SSL_HANDSHAKE ||
            type == ControlMessageType.AUTH_COMPLETE ||
            type == ControlMessageType.VERSION_REQUEST ||
            type == ControlMessageType.VERSION_RESPONSE
        val encrypted = if (shouldEncrypt && !isPlainMessage) {
            tls!!.encrypt(payload)
        } else payload
        val useEncryptedFlag = shouldEncrypt && !isPlainMessage
        val frames = MessageFramer.encode(channelId, encrypted, control, encrypted = useEncryptedFlag)
        frames.forEach { frame -> writeQueue.trySend(frame) }
    }

    override fun onTlsData(data: ByteArray) {
        Log.w(TAG, "TLS handshake data received: ${data.size} bytes")
        val tls = inBandTls ?: return

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

    override fun onTlsComplete() {}

    override fun onServiceDiscoveryRequest(deviceName: String, deviceBrand: String) {
        Log.w(TAG, "Service discovery from: $deviceName ($deviceBrand)")
        val response = ServiceDiscoveryBuilder.build()
        Log.w(TAG, "Sending service discovery response: ${response.size} bytes")
        protocolEngine?.sendServiceDiscoveryResponse(response)
    }

    override fun onServiceDiscoveryResponse(channels: List<ChannelDescriptor>) {
        Log.w(TAG, "Service discovery response: ${channels.size} channels")
        for (ch in channels) {
            Log.w(TAG, "  Channel ${ch.channelId}: av=${ch.hasAv} input=${ch.hasInput} sensor=${ch.hasSensor} nav=${ch.hasNavigation}")
            when {
                ch.hasAv && videoChannelId == -1 -> {
                    videoChannelId = ch.channelId
                    videoChannel = VideoChannel(ch.channelId.toUByte(), this)
                    mediaProjection?.let { videoChannel?.setMediaProjection(it) }
                    Log.w(TAG, "  → Video channel assigned to ${ch.channelId}")
                }
                ch.hasInput -> {
                    inputChannelId = ch.channelId
                    inputChannel = InputChannel(ch.channelId.toUByte(), this)
                    Log.w(TAG, "  → Input channel assigned to ${ch.channelId}")
                }
                ch.hasSensor -> {
                    sensorChannelId = ch.channelId
                    sensorChannel = SensorChannel(ch.channelId.toUByte(), this)
                    Log.w(TAG, "  → Sensor channel assigned to ${ch.channelId}")
                }
                ch.hasAv && videoChannelId != -1 && audioChannelId == -1 -> {
                    audioChannelId = ch.channelId
                    audioOutputChannel = AudioOutputChannel(ch.channelId.toUByte(), this)
                    Log.w(TAG, "  → Audio channel assigned to ${ch.channelId}")
                }
            }
        }
        // Open channels on the head unit after a brief delay
        // to allow the head unit to process the discovery exchange
        scope.launch {
            delay(500)
            Log.w(TAG, "Opening channels on head unit...")
            if (videoChannelId > 0) protocolEngine?.sendChannelOpenRequest(videoChannelId)
            if (inputChannelId > 0) protocolEngine?.sendChannelOpenRequest(inputChannelId)
            if (sensorChannelId > 0) protocolEngine?.sendChannelOpenRequest(sensorChannelId)
        }
    }

    override fun onChannelOpenRequest(channelId: Int, priority: Int) {
        Log.w(TAG, "Channel open request: ch=$channelId priority=$priority")
        protocolEngine?.sendChannelOpenResponse(0) // OK
    }

    override fun onChannelOpened(channelId: Int) {
        Log.w(TAG, "Channel $channelId opened successfully")
        if (channelId == videoChannelId) {
            videoChannel?.sendSetup()
        }
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

    override fun onAudioFocusRequest(focusType: Int) {
        Log.w(TAG, "Audio focus request: type=$focusType")
    }

    override fun onNavigationFocusRequest(type: Int) {
        Log.w(TAG, "Navigation focus request: type=$type")
    }

    override fun onVoiceSessionRequest(type: Int) {
        Log.w(TAG, "Voice session request: type=$type (1=start, 2=stop)")
    }

    // --- VideoChannelCallback ---
    override fun onVideoFrame(channelId: UByte, payload: ByteArray) {
        onSendFrame(channelId, payload, control = false)
    }

    // --- InputChannelCallback ---
    override fun onTouchEvent(event: InputEvent) {
        Log.d(TAG, "Touch: ${event.action} (${event.touchPoints.firstOrNull()?.x},${event.touchPoints.firstOrNull()?.y})")
    }

    override fun onKeyEvent(event: KeyEvent) {
        Log.d(TAG, "Key: scanCode=${event.scanCode} pressed=${event.isPressed}")
    }

    // --- SensorChannelCallback + VideoChannelCallback.onSendMessage ---
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
