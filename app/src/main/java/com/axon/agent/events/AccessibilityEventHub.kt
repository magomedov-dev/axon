package com.axon.agent.events

import com.axon.agent.core.ScreenCounter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Turns the noisy accessibility event stream into clean server-push events.
 *
 * - Only WINDOW_STATE_CHANGED / WINDOW_CONTENT_CHANGED feed screenChanged; scroll/
 *   focus/selection noise never reaches here (the service filters by type).
 * - Trailing debounce: a burst (animation/scroll) collapses into a single emit
 *   once it goes quiet for [debounceMs].
 * - Dedup: the screen counter is bumped (and an event emitted) only on a *real*
 *   change — a window state change, or a new package/window signature. Pure
 *   content churn on the same screen (clocks, tickers) is suppressed.
 *
 * All state is touched only from [dispatcher] (the device's main thread, a single
 * test dispatcher in tests), so no locking is needed. [broadcast] is synchronous
 * and fast; the caller decides how to fan out to connections.
 */
class AccessibilityEventHub(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val screen: ScreenCounter,
    private val debounceMs: Long,
    private val broadcast: (String) -> Unit,
) {
    private var debounceJob: Job? = null
    private var sawStateChange = false
    private var latestPackage: String? = null
    private var latestSignature: String? = null
    private var lastEmittedSignature: String? = null

    /** A window state/content change. [stateChange] = true for WINDOW_STATE_CHANGED. */
    fun onScreenEvent(stateChange: Boolean, packageName: String?, windowId: Int) {
        if (stateChange) sawStateChange = true
        latestPackage = packageName
        latestSignature = "$packageName#$windowId"

        debounceJob?.cancel()
        debounceJob = scope.launch(dispatcher) {
            delay(debounceMs)
            emit()
        }
    }

    private fun emit() {
        val realChange = sawStateChange || latestSignature != lastEmittedSignature
        val pkg = latestPackage
        val signature = latestSignature
        sawStateChange = false
        if (!realChange) return
        lastEmittedSignature = signature
        broadcast(EventMessages.screenChanged(screen.next(), pkg))
    }

    /** A toast / notification. Emitted immediately (no debounce). */
    fun onToast(text: String?, packageName: String?) {
        if (text.isNullOrBlank()) return
        broadcast(EventMessages.toast(text, packageName))
    }
}
