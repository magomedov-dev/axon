package com.axon.agent

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.axon.agent.core.Agent
import com.axon.agent.core.TreeDispatcher
import com.axon.agent.handlers.PingHandler
import com.axon.agent.rpc.JsonRpcDispatcher
import com.axon.agent.rpc.MethodRouter
import com.axon.agent.rpc.RpcContext
import com.axon.agent.server.WsServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.concurrent.thread

/**
 * Entry point and single owner of the runtime: the coroutine [scope], the
 * single-thread [tree] dispatcher, and the in-process [WsServer]. One process,
 * one scope, no IPC bridge.
 */
class AutomationAccessibilityService : AccessibilityService(), Agent {

    override val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    override val tree: TreeDispatcher = TreeDispatcher()

    private val dispatcher: JsonRpcDispatcher by lazy {
        JsonRpcDispatcher(
            MethodRouter(
                mapOf(
                    "ping" to PingHandler,
                )
            )
        )
    }

    private var server: WsServer? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        startServer()
        Log.i(TAG, "onServiceConnected: service up, server starting")
    }

    private fun startServer() {
        if (server != null) return
        val s = WsServer(
            port = PORT,
            scope = scope,
            onConnect = { Log.d(TAG, "client connected #${it.id}") },
            onDisconnect = { Log.d(TAG, "client disconnected #${it.id}") },
            process = { message, state -> dispatcher.dispatch(message, RpcContext(state, this)) },
        )
        runCatching { s.start() }
            .onSuccess { server = s }
            .onFailure { Log.e(TAG, "failed to start WS server", it) }
    }

    private fun stopServer() {
        val s = server ?: return
        server = null
        // stop() blocks; never run it on the main thread.
        thread(name = "axon-ws-stop") { runCatching { s.stop(STOP_TIMEOUT_MS) } }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Stage 7: screenChanged/toast with debounce. No-op for now.
    }

    override fun onInterrupt() {
        Log.w(TAG, "onInterrupt")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG, "onUnbind")
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        instance = null
        stopServer()
        scope.cancel()
        runCatching { tree.close() }
    }

    // ---- status surface for the UI ---------------------------------------
    fun isServerRunning(): Boolean = server?.started == true
    fun connectionCount(): Int = server?.connectionCount() ?: 0

    companion object {
        const val TAG = "AxonService"
        const val PORT = 9008
        private const val STOP_TIMEOUT_MS = 500

        /** Non-null only while the service is connected. Read by the status UI. */
        @Volatile
        var instance: AutomationAccessibilityService? = null
            private set
    }
}
