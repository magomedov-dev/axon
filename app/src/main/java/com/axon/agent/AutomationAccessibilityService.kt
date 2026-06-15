package com.axon.agent

import android.content.Intent
import android.util.Log
import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Entry point of the agent. The accessibility service is the single process that
 * (from Stage 1 onward) will also own the WebSocket server, the coroutine scope,
 * and the single-thread tree dispatcher. No IPC bridge, no extra process.
 *
 * Stage 0: bare lifecycle only — it exists, registers, and exposes a liveness
 * handle ([instance]) so the status UI can tell whether the service is connected.
 */
class AutomationAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "onServiceConnected: accessibility service is up")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Stage 0: no-op. Real event handling (screenChanged/toast, debounce) lands in Stage 7.
    }

    override fun onInterrupt() {
        Log.w(TAG, "onInterrupt")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG, "onUnbind")
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        instance = null
        super.onDestroy()
    }

    companion object {
        const val TAG = "AxonService"

        /** Non-null only while the service is connected. Read by the status UI. */
        @Volatile
        var instance: AutomationAccessibilityService? = null
            private set
    }
}
