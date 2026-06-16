package com.axon.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
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
import com.axon.agent.ui.StatusActivity
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import com.axon.agent.rpc.MethodRouter
import com.axon.agent.rpc.RpcContext
import com.axon.agent.server.WsServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

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

    // Future seam (getWindows): the accessibility config enables
    // flagRetrieveInteractiveWindows, so `windows` / getWindows() are available to
    // add a multi-window dump without changing the architecture.

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

    @Volatile
    private var server: WsServer? = null
    private var eventHub: AccessibilityEventHub? = null

    // Server start/stop are serialized on one thread so a stop fully completes
    // (port released) before any restart — otherwise a quick toggle off→on would
    // leave a dying server racing a new one on the same port.
    private val serverLifecycle = Executors.newSingleThreadExecutor { r ->
        Thread(r, "axon-ws-lifecycle")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        startForegroundNotification()
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

    /**
     * Promote to a foreground service so the system is far less likely to kill it
     * while the controlling app is backgrounded. specialUse type: the FGS exists
     * solely to keep the local WebSocket control channel alive.
     *
     * The specialUse FGS *type* is enforced only on API 34+ (paired with the
     * manifest PROPERTY_SPECIAL_USE_FGS_SUBTYPE). On API 30–33 the type argument is
     * ignored but startForeground still promotes the service (verified on API 33).
     */
    private fun startForegroundNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
        val tap = PendingIntent.getActivity(
            this,
            0,
            Intent(this, StatusActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.service_label))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(tap)
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

    private fun startServer() = serverLifecycle.execute { doStartServer() }

    private fun stopServer() = serverLifecycle.execute { doStopServer() }

    private fun doStartServer() {
        if (server != null) return
        val s = WsServer(
            port = PORT,
            scope = scope,
            onConnect = { Log.d(TAG, "client connected #${it.id}") },
            onDisconnect = { Log.d(TAG, "client disconnected #${it.id}") },
            process = { message, state -> dispatcher.dispatch(message, RpcContext(state, this)) },
        )
        runCatching { s.start() }
            .onSuccess { server = s; Log.i(TAG, "WS server started") }
            .onFailure { Log.e(TAG, "failed to start WS server", it) }
    }

    private fun doStopServer() {
        val s = server ?: return
        server = null
        runCatching { s.stop(STOP_TIMEOUT_MS) } // blocking, but on the lifecycle thread
        Log.i(TAG, "WS server stopped")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        val hub = eventHub ?: return
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ->
                hub.onScreenEvent(e.packageName?.toString())
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
        serverLifecycle.shutdown() // lets the queued stop run, then terminates
        scope.cancel()
        runCatching { tree.close() }
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
    }

    // ---- status surface for the UI ---------------------------------------
    fun isServerRunning(): Boolean = server?.started == true
    fun connectionCount(): Int = server?.connectionCount() ?: 0

    /** Start/stop the WebSocket server from the status UI toggle. */
    fun setServerEnabled(enabled: Boolean) {
        if (enabled) startServer() else stopServer()
    }

    companion object {
        const val TAG = "AxonService"
        const val PORT = 9008
        private const val STOP_TIMEOUT_MS = 500
        private const val EVENT_DEBOUNCE_MS = 80L
        private const val CHANNEL_ID = "axon_status"
        private const val NOTIF_ID = 1

        /** Non-null only while the service is connected. Read by the status UI. */
        @Volatile
        var instance: AutomationAccessibilityService? = null
            private set
    }
}
