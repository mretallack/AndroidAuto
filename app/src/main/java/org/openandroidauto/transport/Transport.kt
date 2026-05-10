package org.openandroidauto.transport

import java.nio.ByteBuffer

interface Transport {
    suspend fun connect()
    suspend fun disconnect()
    suspend fun read(buffer: ByteBuffer): Int
    suspend fun write(buffer: ByteBuffer)
    val isConnected: Boolean
}
