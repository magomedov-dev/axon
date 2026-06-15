package com.axon.agent.core

import android.accessibilityservice.GestureDescription
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope

/**
 * The capabilities RPC handlers need from the host, abstracted away from the
 * Android AccessibilityService so handlers and the dispatcher stay unit-testable
 * off-device. [com.axon.agent.AutomationAccessibilityService] is the production
 * implementation.
 *
 * The surface grows per stage (screenshots, event broadcast, gestures).
 */
interface Agent {
    /** Service-scoped coroutine scope; cancelled when the service dies. */
    val scope: CoroutineScope

    /** Single-thread confinement for all AccessibilityNodeInfo work. */
    val tree: TreeDispatcher

    /** Shared screen-state generation counter. */
    val screen: ScreenCounter

    /**
     * A FRESH root of the active window, or null if unavailable (service off / no
     * foreground window). Never cached. Call on the [tree] thread.
     */
    fun rootNode(): AccessibilityNodeInfo?

    /**
     * Dispatch a gesture and suspend until it finishes. Returns true on
     * onCompleted, false if it was cancelled or could not be dispatched.
     */
    suspend fun performGesture(gesture: GestureDescription): Boolean
}
