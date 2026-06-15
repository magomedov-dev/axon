package com.axon.agent.server

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer

/**
 * Serializes all writes to one connection.
 *
 * A [Mutex] guarantees that concurrent coroutines never interleave frames — in
 * particular the screenshot path (JSON metadata immediately followed by a binary
 * frame) must go out as one atomic back-to-back pair, with no other message
 * slipping between them. The binary frame is `[4-byte id BE][payload]`.
 */
class FrameWriter(private val sender: Sender) {

    private val mutex = Mutex()

    suspend fun sendText(text: String) = mutex.withLock {
        if (sender.isOpen) sender.sendText(text)
    }

    /** JSON metadata, then its binary frame, atomically and in order. */
    suspend fun sendJsonThenBinary(json: String, id: Long, payload: ByteArray) = mutex.withLock {
        if (sender.isOpen) {
            sender.sendText(json)
            sender.sendBinary(frame(id, payload))
        }
    }

    private fun frame(id: Long, payload: ByteArray): ByteArray =
        ByteBuffer.allocate(4 + payload.size)
            .putInt(id.toInt())          // uint32 big-endian (ByteBuffer is BE by default)
            .put(payload)
            .array()
}
