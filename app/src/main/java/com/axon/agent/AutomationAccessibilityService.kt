package com.axon.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.axon.agent.core.Agent
import com.axon.agent.core.ScreenCounter
import com.axon.agent.core.TreeDispatcher
import com.axon.agent.events.AccessibilityEventHub
import com.axon.agent.handlers.DumpHandler
import com.axon.agent.handlers.EventStreamHandler
import com.axon.agent.handlers.GestureHandler
import com.axon.agent.handlers.GlobalActionHandler
import com.axon.agent.handlers.NodeActionHandler
import com.axon.agent.handlers.PingHandler
import com.axon.agent.handlers.ScreenshotHandler
import com.axon.agent.rpc.ErrorCodes
import com.axon.agent.rpc.JsonRpcDispatcher
import com.axon.agent.rpc.RpcException
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
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
    override val screen: ScreenCounter = ScreenCounter()

    override fun rootNode(): AccessibilityNodeInfo? = rootInActiveWindow

    override suspend fun performGesture(gesture: GestureDescription): Boolean =
        suspendCancellableCoroutine { cont ->
            val callback = object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) {
                    if (cont.isActive) cont.resumeWith(Result.success(true))
                }

                override fun onCancelled(g: GestureDescription?) {
                    if (cont.isActive) cont.resumeWith(Result.success(false))
                }
            }
            // null handler -> callbacks arrive on the main thread.
            val dispatched = dispatchGesture(gesture, callback, null)
            if (!dispatched && cont.isActive) cont.resumeWith(Result.success(false))
        }

    override suspend fun captureScreenshot(): Bitmap =
        suspendCancellableCoroutine { cont ->
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        val buffer = screenshot.hardwareBuffer
                        try {
                            val hw = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                            val software = hw?.copy(Bitmap.Config.ARGB_8888, false)
                            hw?.recycle()
                            if (software != null) {
                                cont.resumeWith(Result.success(software))
                            } else {
                                cont.resumeWith(
                                    Result.failure(RpcException(ErrorCodes.INTERNAL, "failed to decode screenshot buffer"))
                                )
                            }
                        } catch (e: Throwable) {
                            cont.resumeWith(Result.failure(e))
                        } finally {
                            buffer.close()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        cont.resumeWith(
                            Result.failure(RpcException(ErrorCodes.INTERNAL, "takeScreenshot failed (code $errorCode)"))
                        )
                    }
                },
            )
        }

    private val dispatcher: JsonRpcDispatcher by lazy {
        JsonRpcDispatcher(
            MethodRouter(
                mapOf(
                    "ping" to PingHandler,
                    "dumpHierarchy" to DumpHandler,
                    "gesture" to GestureHandler,
                    "nodeAction" to NodeActionHandler,
                    "globalAction" to GlobalActionHandler,
                    "screenshot" to ScreenshotHandler,
                    "setEventStream" to EventStreamHandler,
                )
            )
        )
    }

    private var server: WsServer? = null
    private var eventHub: AccessibilityEventHub? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        startServer()
        eventHub = AccessibilityEventHub(
            scope = scope,
            dispatcher = Dispatchers.Main,
            screen = screen,
            debounceMs = EVENT_DEBOUNCE_MS,
            broadcast = ::broadcastEvent,
        )
        Log.i(TAG, "onServiceConnected: service up, server starting")
    }

    /** Fan a server-push event out to subscribed connections (off the main thread). */
    private fun broadcastEvent(json: String) {
        val srv = server ?: return
        scope.launch {
            for (connection in srv.connections()) {
                if (connection.eventStream) {
                    runCatching { connection.writer.sendText(json) }
                }
            }
        }
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
        val e = event ?: return
        val hub = eventHub ?: return
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ->
                hub.onScreenEvent(stateChange = true, e.packageName?.toString(), e.windowId)
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ->
                hub.onScreenEvent(stateChange = false, e.packageName?.toString(), e.windowId)
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED ->
                hub.onToast(joinText(e.text), e.packageName?.toString())
            // everything else (scroll/focus/selection) is noise — ignore.
        }
    }

    private fun joinText(text: List<CharSequence>?): String? =
        text?.joinToString(" ") { it.toString() }?.trim()?.takeIf { it.isNotEmpty() }

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
        eventHub = null
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
        private const val EVENT_DEBOUNCE_MS = 80L

        /** Non-null only while the service is connected. Read by the status UI. */
        @Volatile
        var instance: AutomationAccessibilityService? = null
            private set
    }
}
