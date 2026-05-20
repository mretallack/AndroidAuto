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
import org.openandroidauto.input.TouchInjector
import org.openandroidauto.protocol.*
import org.openandroidauto.tls.AaTlsServer
import org.openandroidauto.tls.InBandTls
import org.openandroidauto.transport.TcpTransport
import org.openandroidauto.transport.Transport
import org.openandroidauto.transport.UsbAoaTransport
import org.openandroidauto.util.FileLogger
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
    private val priorityWriteQueue = CoChannel<ByteArray>(capacity = 16)
    private var transport: Transport? = null
    private var protocolEngine: ProtocolEngine? = null
    private var inBandTls: InBandTls? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var mediaProjection: MediaProjection? = null
    private var framesSent = 0L
    private var framesReceived = 0L
    private val touchInjector = TouchInjector()

    private fun logW(msg: String) {
        Log.w(TAG, msg)
        FileLogger.log(TAG, msg)
    }

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
    private var audioSilenceJob: kotlinx.coroutines.Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        FileLogger.init(this)
        logW("Service created")
        FileLogger.log(TAG, "Service created")
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Connecting..."),
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        logW("Service starting")
        // Extract MediaProjection if provided
        // Note: Activity.RESULT_OK = -1, so we use a different sentinel
        val resultCode = intent?.getIntExtra(EXTRA_PROJECTION_RESULT_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val projectionData: Intent? = intent?.getParcelableExtra(EXTRA_PROJECTION_DATA)
        if (resultCode != 0 && projectionData != null) {
            val projectionManager = getSystemService(android.media.projection.MediaProjectionManager::class.java)
            mediaProjection = projectionManager.getMediaProjection(resultCode, projectionData)
            logW("MediaProjection obtained successfully")
        } else {
            logW("No MediaProjection - will send black frames")
        }
        scope.launch { startSession() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        logW("Service destroying. Frames sent=$framesSent received=$framesReceived")
        FileLogger.close()
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
            val activeTransport: Transport = try {
                logW("Looking for USB accessory...")
                val usbTransport = UsbAoaTransport(this)
                logW("Connecting to USB accessory...")
                usbTransport.connect()
                logW("USB connected successfully")
                usbTransport
            } catch (e: Exception) {
                // Try connecting to openauto/DHU on localhost:5000 (via adb reverse)
                try {
                    logW("No USB, trying TCP client to localhost:5000 (openauto)...")
                    val tcpClient = TcpTransport("127.0.0.1", 5000)
                    tcpClient.connect()
                    logW("Connected to openauto via TCP")
                    tcpClient
                } catch (e2: Exception) {
                    logW("No openauto, starting TCP server on port 5277 for DHU...")
                    val tcpTransport = org.openandroidauto.transport.TcpServerTransport(5277)
                    tcpTransport.connect()
                    logW("DHU connected via TCP")
                    tcpTransport
                }
            }

            logW("Initializing TLS...")
            val tlsServer = AaTlsServer(AaTlsServer.createKeyStore(this@ProjectionService))
            val engine = tlsServer.createEngine()
            inBandTls = InBandTls(engine)
            logW("TLS engine created (server mode, TLSv1.2)")

            transport = activeTransport
            protocolEngine = ProtocolEngine(this)

            logW("Starting protocol - waiting for head unit VERSION_REQUEST")
            protocolEngine?.start()

            scope.launch { writeLoop() }
            readLoop(activeTransport)
        } catch (e: Exception) {
            Log.e(TAG, "Session failed: ${e.message}", e)
            updateNotification("Error: ${e.message}")
            stopSelf()
        }
    }

    private suspend fun readLoop(transport: Transport) {
        val buffer = ByteBuffer.allocate(32768)
        val decoder = MessageFramer.Decoder()
        logW("Read loop started")

        while (scope.isActive) {
            val read = transport.read(buffer)
            if (read <= 0) {
                logW("Transport read returned $read, disconnecting")
                break
            }

            framesReceived++
            val pos = buffer.position()
            val rawHex = StringBuilder()
            for (i in 0 until minOf(pos, 32)) { rawHex.append(String.format("%02x ", buffer[i])) }
            logW("RAW ← $pos bytes: $rawHex")

            buffer.flip()
            val messages = decoder.decode(buffer)
            buffer.compact()

            for (msg in messages) {
                routeMessage(msg)
            }
        }
        logW("Read loop ended")
        stopSelf()
    }

    private suspend fun writeLoop() {
        logW("Write loop started")
        try {
            while (scope.isActive) {
                // Priority queue first, then regular
                val frame = priorityWriteQueue.tryReceive().getOrNull()
                    ?: writeQueue.tryReceive().getOrNull()
                if (frame != null) {
                    transport?.write(ByteBuffer.wrap(frame))
                } else {
                    // Wait for either queue using select
                    val selected = kotlinx.coroutines.selects.select<ByteArray> {
                        priorityWriteQueue.onReceive { it }
                        writeQueue.onReceive { it }
                    }
                    transport?.write(ByteBuffer.wrap(selected))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Write loop error: ${e.message}")
        }
        logW("Write loop ended")
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
                logW("Feeding ${msg.payload.size} bytes to TLS (possible encrypted flight)")
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
                logW("  decrypt: ${msg.payload.size}→${decrypted.size} pre=[$preHex] post=[$postHex]")
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
            logW("← ch=$chId CHANNEL_OPEN_RESPONSE status=OK")
            protocolEngine?.onMessage(type, msgPayload)
            return
        }

        logW("← ch=$chId type=0x${type.toString(16)} len=${payload.size} enc=${isEncryptedFrame || isTlsRecord} ctrl=$isControl")

        // Log hex for short/unknown messages for debugging
        if (payload.size <= 10) {
            val hex = payload.joinToString(" ") { String.format("%02x", it) }
            logW("  payload hex: $hex")
        }

        // 0x00FF - unknown message from head unit. Could be:
        // - A NACK/unsupported indicator
        // - A message we're decrypting incorrectly
        // Try interpreting the raw TLS record differently
        if (chId == 0 && payload.size == 2 && type == 0x00FF) {
            logW("  Unknown 0x00FF message - may indicate protocol mismatch")
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
                else logW("Unrouted message ch=$chId type=0x${type.toString(16)}")
            }
        }
    }

    // --- ProtocolCallback ---

    override fun onSendFrame(channelId: UByte, payload: ByteArray, control: Boolean) {
        framesSent++
        if (payload.size >= 2) {
            val type = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
            logW("→ ch=$channelId type=0x${type.toString(16)} len=${payload.size} ctrl=$control")
        }
        val tls = inBandTls
        val shouldEncrypt = tls != null && tls.isHandshakeComplete &&
            protocolEngine?.state != ProtocolState.TLS_HANDSHAKE
        val type = if (payload.size >= 2) ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF) else 0
        val isPlainMessage = type == ControlMessageType.SSL_HANDSHAKE ||
            type == ControlMessageType.AUTH_COMPLETE ||
            type == ControlMessageType.VERSION_REQUEST ||
            type == ControlMessageType.VERSION_RESPONSE

        if (shouldEncrypt && !isPlainMessage) {
            // Fragment-before-encrypt (AACS style):
            // Split plaintext into 2KB chunks, encrypt each separately
            val maxChunk = 2000
            val totalPlaintextLength = payload.size
            val queue = if (channelId.toInt() == 0) priorityWriteQueue else writeQueue

            if (payload.size <= maxChunk) {
                // Small message - encrypt and send as SINGLE (Bulk = First|Last)
                val encrypted = tls!!.encrypt(payload)
                val header = FrameHeader.create(channelId, FrameHeader.FrameType.SINGLE, control, encrypted = true)
                val frame = MessageFramer.buildFrame(header, encrypted)
                queue.trySend(frame)
            } else {
                // Large message - split, encrypt each chunk, FIRST/MIDDLE/LAST
                val numChunks = (payload.size + maxChunk - 1) / maxChunk
                for (i in 0 until numChunks) {
                    val offset = i * maxChunk
                    val end = minOf(offset + maxChunk, payload.size)
                    val chunk = payload.copyOfRange(offset, end)
                    val encrypted = tls!!.encrypt(chunk)

                    val frameType = when {
                        i == 0 -> FrameHeader.FrameType.FIRST
                        i == numChunks - 1 -> FrameHeader.FrameType.LAST
                        else -> FrameHeader.FrameType.MIDDLE
                    }
                    val header = FrameHeader.create(channelId, frameType, control, encrypted = true)
                    val frame = if (i == 0) {
                        // FIRST: [ch][flags][chunk_len:2][total_plaintext_len:4][encrypted]
                        MessageFramer.buildFirstFrame(header, encrypted, totalPlaintextLength)
                    } else {
                        // MIDDLE/LAST: [ch][flags][chunk_len:2][encrypted]
                        MessageFramer.buildFrame(header, encrypted)
                    }
                    queue.trySend(frame)
                }
            }
        } else {
            // Plain message
            val frames = MessageFramer.encode(channelId, payload, control, encrypted = false)
            val queue = if (channelId.toInt() == 0) priorityWriteQueue else writeQueue
            frames.forEach { frame -> queue.trySend(frame) }
        }
    }

    override fun onTlsData(data: ByteArray) {
        logW("TLS handshake data received: ${data.size} bytes")
        val tls = inBandTls ?: return

        if (!tls.isHandshakeComplete) {
            val initialResponses = tls.beginHandshake()
            for (record in initialResponses) {
                logW("TLS → sending ${record.size} bytes (initial)")
                protocolEngine?.sendTlsData(record)
            }
        }

        val responses = tls.feedHandshakeData(data)
        for (record in responses) {
            logW("TLS → sending ${record.size} bytes")
            protocolEngine?.sendTlsData(record)
        }
        if (tls.isHandshakeComplete) {
            logW("TLS handshake complete, notifying protocol engine")
            protocolEngine?.onTlsHandshakeComplete()
        }
    }

    override fun onTlsComplete() {}

    override fun onServiceDiscoveryRequest(deviceName: String, deviceBrand: String) {
        logW("Service discovery from: $deviceName ($deviceBrand)")
        val response = ServiceDiscoveryBuilder.build()
        logW("Sending service discovery response: ${response.size} bytes")
        protocolEngine?.sendServiceDiscoveryResponse(response)
    }

    override fun onServiceDiscoveryResponse(channels: List<ChannelDescriptor>) {
        logW("Service discovery response: ${channels.size} channels")
        for (ch in channels) {
            logW("  Channel ${ch.channelId}: av=${ch.hasAv} input=${ch.hasInput} sensor=${ch.hasSensor} nav=${ch.hasNavigation}")
            when {
                ch.hasAv && videoChannelId == -1 -> {
                    videoChannelId = ch.channelId
                    videoChannel = VideoChannel(ch.channelId.toUByte(), this)
                    mediaProjection?.let { videoChannel?.setMediaProjection(it) }
                    logW("  → Video channel assigned to ${ch.channelId}")
                }
                ch.hasInput -> {
                    inputChannelId = ch.channelId
                    inputChannel = InputChannel(ch.channelId.toUByte(), this)
                    logW("  → Input channel assigned to ${ch.channelId}")
                }
                ch.hasSensor -> {
                    sensorChannelId = ch.channelId
                    sensorChannel = SensorChannel(ch.channelId.toUByte(), this)
                    logW("  → Sensor channel assigned to ${ch.channelId}")
                }
                ch.hasAv && videoChannelId != -1 && audioChannelId == -1 -> {
                    audioChannelId = ch.channelId
                    audioOutputChannel = AudioOutputChannel(ch.channelId.toUByte(), this)
                    logW("  → Audio channel assigned to ${ch.channelId}")
                }
            }
        }
        // Open channels on the head unit after a brief delay
        scope.launch {
            delay(500)
            logW("Opening channels on head unit...")
            if (videoChannelId > 0) protocolEngine?.sendChannelOpenRequest(videoChannelId)
            if (audioChannelId > 0) protocolEngine?.sendChannelOpenRequest(audioChannelId)
            if (inputChannelId > 0) protocolEngine?.sendChannelOpenRequest(inputChannelId)
            if (sensorChannelId > 0) protocolEngine?.sendChannelOpenRequest(sensorChannelId)
        }
    }

    private fun sendBluetoothPairingRequest() {
        // BluetoothPairingRequest: field 1 (phone_address) string, field 2 (pairing_method) varint
        // Use a dummy BT address - the head unit needs to see this message
        val btAddr = "00:00:00:00:00:00".toByteArray()
        val out = java.io.ByteArrayOutputStream()
        // field 1: phone_address (string)
        out.write((1 shl 3) or 2) // tag
        out.write(btAddr.size)
        out.write(btAddr)
        // field 2: pairing_method = A2DP(2)
        out.write((2 shl 3) or 0) // tag
        out.write(2) // A2DP
        val payload = out.toByteArray()
        // BluetoothPairingRequest message type = 0x8001 on the bluetooth channel
        // But we send it on control channel as per AACS
        val msg = java.nio.ByteBuffer.allocate(2 + payload.size)
            .order(java.nio.ByteOrder.BIG_ENDIAN)
            .putShort(0x8001.toShort()) // PAIRING_REQUEST
            .put(payload)
            .array()
        // Find bluetooth channel (channel 5 typically)
        val btChannelId = protocolEngine?.discoveredChannels?.firstOrNull {
            !it.hasAv && !it.hasInput && !it.hasSensor && !it.hasNavigation && it.channelId == 5
        }?.channelId
        if (btChannelId != null && btChannelId > 0) {
            logW("Sending BluetoothPairingRequest on channel $btChannelId")
            // Open BT channel first
            protocolEngine?.sendChannelOpenRequest(btChannelId)
            scope.launch {
                delay(500)
                onSendFrame(btChannelId.toUByte(), msg, control = false)
            }
        } else {
            logW("No Bluetooth channel found, skipping pairing")
        }
    }

    override fun onChannelOpenRequest(channelId: Int, priority: Int) {
        logW("Channel open request: ch=$channelId priority=$priority")
        protocolEngine?.sendChannelOpenResponse(0) // OK
    }

    override fun onChannelOpened(channelId: Int) {
        logW("Channel $channelId opened successfully")
        if (channelId == videoChannelId) {
            videoChannel?.sendSetup()
        }
        if (channelId == audioChannelId) {
            // Send audio SETUP
            val setupPayload = byteArrayOf(0x08, 0x01) // MEDIA_CODEC_AUDIO_PCM
            val msg = java.nio.ByteBuffer.allocate(2 + setupPayload.size)
                .order(java.nio.ByteOrder.BIG_ENDIAN)
                .putShort(0x8000.toShort())
                .put(setupPayload)
                .array()
            logW("Sending audio SETUP (PCM) on channel $channelId")
            onSendFrame(channelId.toUByte(), msg, control = false)
            // Don't start audio silence - it may interfere with video streaming
            // scope.launch { delay(1000); startAudioSilence() }.also { audioSilenceJob = it }
        }
        if (channelId == inputChannelId) {
            val bindingPayload = byteArrayOf(0x08, 0x01, 0x08, 0x04, 0x08, 0x03)
            val msg = java.nio.ByteBuffer.allocate(2 + bindingPayload.size)
                .order(java.nio.ByteOrder.BIG_ENDIAN)
                .putShort(0x8002.toShort())
                .put(bindingPayload)
                .array()
            logW("Sending input BINDING_REQUEST on channel $channelId")
            onSendFrame(channelId.toUByte(), msg, control = false)
        }
    }

    /** Send silence (PCM zeros) on the audio channel to keep it active */
    private fun startAudioSilence() {
        if (audioChannelId <= 0) return
        logW("Starting audio silence on channel $audioChannelId")
        scope.launch {
            // Send START indication on audio channel
            val startPayload = byteArrayOf(0x08, 0x01, 0x10, 0x00) // session=1, config=0
            val startMsg = java.nio.ByteBuffer.allocate(2 + startPayload.size)
                .order(java.nio.ByteOrder.BIG_ENDIAN)
                .putShort(0x8001.toShort()) // START_INDICATION
                .put(startPayload)
                .array()
            onSendFrame(audioChannelId.toUByte(), startMsg, control = false)

            // Stream silence: 48kHz stereo 16-bit PCM = 192000 bytes/sec
            // Send 50ms chunks = 9600 bytes every 50ms
            val silenceChunk = ByteArray(9600) // 50ms of silence at 48kHz stereo 16-bit
            var timestampUs = 0L
            val chunkDurationUs = 50_000L // 50ms in microseconds
            while (scope.isActive) {
                val frame = java.nio.ByteBuffer.allocate(2 + 8 + silenceChunk.size)
                    .order(java.nio.ByteOrder.BIG_ENDIAN)
                    .putShort(0x0000.toShort()) // AV_MEDIA_WITH_TIMESTAMP
                    .putLong(timestampUs)
                    .put(silenceChunk)
                    .array()
                onSendFrame(audioChannelId.toUByte(), frame, control = false)
                timestampUs += chunkDurationUs
                delay(50)
            }
        }
    }

    override fun onActive() {
        logW("Protocol ACTIVE - connection established!")
        updateNotification("Connected")
        // Send AUDIO_FOCUS_REQUEST (GAIN) - HUIG: MD MUST request focus before playing
        protocolEngine?.sendAudioFocusRequest(1) // GAIN=1
        // Start sending periodic pings to keep connection alive
        scope.launch {
            while (scope.isActive) {
                delay(1000)
                protocolEngine?.sendPingRequest(System.currentTimeMillis() * 1000)
            }
        }
    }

    override fun onShutdown() {
        logW("Shutdown requested")
        updateNotification("Disconnected")
        stopSelf()
    }

    override fun onAudioFocusRequest(focusType: Int) {
        logW("Audio focus request: type=$focusType")
    }

    override fun onNavigationFocusRequest(type: Int) {
        logW("Navigation focus request: type=$type")
    }

    override fun onVoiceSessionRequest(type: Int) {
        logW("Voice session request: type=$type (1=start, 2=stop)")
    }

    // --- VideoChannelCallback ---
    override fun onVideoFrame(channelId: UByte, payload: ByteArray) {
        onSendFrame(channelId, payload, control = false)
    }

    // --- InputChannelCallback ---
    override fun onTouchEvent(event: InputEvent) {
        Log.d(TAG, "Touch: ${event.action} (${event.touchPoints.firstOrNull()?.x},${event.touchPoints.firstOrNull()?.y})")
        touchInjector.inject(event)
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
        logW("Status: $text")
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
