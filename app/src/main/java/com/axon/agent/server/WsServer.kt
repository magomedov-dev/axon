package com.axon.agent.server

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * WebSocket server living inside the accessibility service (one process, one
 * scope, no IPC). Accepts connections on 0.0.0.0:port; each text message is
 * handed to [process] on a coroutine and its reply written back through the
 * connection's serialized [FrameWriter].
 *
 * The library's own ping/pong is disabled (connectionLostTimeout = 0) — liveness
 * is proven at the application layer via the JSON-RPC `ping` method, because a
 * TCP socket can look alive while the service logic is already dead.
 */
class WsServer(
    port: Int,
    private val scope: CoroutineScope,
    private val onConnect: (ConnectionState) -> Unit,
    private val onDisconnect: (ConnectionState) -> Unit,
    private val process: suspend (String, ConnectionState) -> String?,
) : WebSocketServer(InetSocketAddress("0.0.0.0", port)) {

    private val states = ConcurrentHashMap<WebSocket, ConnectionState>()
    private val ids = AtomicLong(0)

    @Volatile
    var started: Boolean = false
        private set

    init {
        isReuseAddr = true
        connectionLostTimeout = 0
    }

    fun connectionCount(): Int = states.size

    /** Live connections — used by the event broadcaster (Stage 7). */
    fun connections(): Collection<ConnectionState> = states.values

    override fun onStart() {
        started = true
        Log.i(TAG, "listening on $address")
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake?) {
        val state = ConnectionState(ids.incrementAndGet(), senderFor(conn))
        states[conn] = state
        onConnect(state)
        Log.i(TAG, "open #${state.id} from ${conn.remoteSocketAddress} (total ${states.size})")
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
        states.remove(conn)?.let {
            onDisconnect(it)
            Log.i(TAG, "close #${it.id} code=$code (total ${states.size})")
        }
    }

    override fun onMessage(conn: WebSocket, message: String) {
        val state = states[conn] ?: return
        scope.launch {
            try {
                val reply = process(message, state)
                if (reply != null) state.writer.sendText(reply)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "message handling failed on #${state.id}", e)
            }
        }
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        // conn == null means a server-level error (e.g. failed to bind the port).
        Log.e(TAG, "ws error (conn=${conn?.remoteSocketAddress})", ex)
    }

    private fun senderFor(conn: WebSocket) = object : Sender {
        override val isOpen: Boolean get() = conn.isOpen
        override fun sendText(text: String) = conn.send(text)
        override fun sendBinary(bytes: ByteArray) = conn.send(bytes)
    }

    companion object {
        const val TAG = "AxonServer"
    }
}
