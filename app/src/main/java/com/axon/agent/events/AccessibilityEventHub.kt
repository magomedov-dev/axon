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
 *   once it goes quiet for [debounceMs]; an animation that never settles produces
 *   nothing until it stops.
 * - Every settled change — including in-place content changes within the SAME
 *   window (an error message appearing, a section expanding) — emits a
 *   screenChanged with a freshly incremented `screen`. This is deliberate: a
 *   client waiting for an element on the event stream must get a "recheck" signal
 *   rather than silence, otherwise it has to fall back to polling. Pure churn that
 *   never settles is still suppressed by the debounce; clients confirm with a dump.
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
    private var latestPackage: String? = null

    /** A window state or content change (both feed the same debounced signal). */
    fun onScreenEvent(packageName: String?) {
        latestPackage = packageName
        debounceJob?.cancel()
        debounceJob = scope.launch(dispatcher) {
            delay(debounceMs)
            broadcast(EventMessages.screenChanged(screen.next(), latestPackage))
        }
    }

    /** A toast / notification. Emitted immediately (no debounce). */
    fun onToast(text: String?, packageName: String?) {
        if (text.isNullOrBlank()) return
        broadcast(EventMessages.toast(text, packageName))
    }
}
