package com.axon.agent.server

/**
 * Minimal write side of a connection. Abstracts the underlying transport
 * (Java-WebSocket) so [FrameWriter] and the app layer don't depend on the
 * library directly — which also makes them trivial to fake in unit tests.
 */
interface Sender {
    val isOpen: Boolean
    fun sendText(text: String)
    fun sendBinary(bytes: ByteArray)
}
