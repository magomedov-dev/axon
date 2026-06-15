package com.axon.agent.server

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameWriterTest {

    @Test
    fun sendText_writesWhenOpen() = runTest {
        val sender = RecordingSender(isOpen = true)
        FrameWriter(sender).sendText("hello")
        assertEquals(listOf("text:hello"), sender.log)
    }

    @Test
    fun sendText_silentWhenClosed() = runTest {
        val sender = RecordingSender(isOpen = false)
        FrameWriter(sender).sendText("hello")
        assertTrue(sender.log.isEmpty())
    }

    @Test
    fun sendJsonThenBinary_ordersTextThenFramedBinary() = runTest {
        val sender = RecordingSender(isOpen = true)
        val payload = byteArrayOf(10, 20, 30)
        FrameWriter(sender).sendJsonThenBinary("""{"id":42}""", id = 42, payload = payload)

        assertEquals(2, sender.log.size)
        assertEquals("""text:{"id":42}""", sender.log[0])

        val binary = sender.binaries.single()
        // [4-byte id uint32 BE][payload]
        assertArrayEquals(byteArrayOf(0, 0, 0, 42), binary.copyOfRange(0, 4))
        assertArrayEquals(payload, binary.copyOfRange(4, binary.size))
    }

    private class RecordingSender(override val isOpen: Boolean) : Sender {
        val log = mutableListOf<String>()
        val binaries = mutableListOf<ByteArray>()
        override fun sendText(text: String) { log.add("text:$text") }
        override fun sendBinary(bytes: ByteArray) { log.add("binary"); binaries.add(bytes) }
    }
}
