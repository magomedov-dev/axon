package com.axon.agent.core

import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
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
     * All interactive windows (application, IME, system, dialogs, overlays,
     * split-screen). Fresh each call; the caller recycles them. Call on the [tree]
     * thread. Satisfied by AccessibilityService.getWindows() on the device.
     */
    fun windowInfos(): List<AccessibilityWindowInfo>

    /**
     * Dispatch a gesture and suspend until it finishes. Returns true on
     * onCompleted, false if it was cancelled or could not be dispatched.
     */
    suspend fun performGesture(gesture: GestureDescription): Boolean

    /**
     * Perform a system global action (GLOBAL_ACTION_*). Returns the platform
     * result. Satisfied by AccessibilityService.performGlobalAction on the device.
     */
    fun performGlobalAction(action: Int): Boolean

    /**
     * Capture the screen via takeScreenshot() and return a software Bitmap.
     * Throws [com.axon.agent.rpc.RpcException] on failure (e.g. rate-limited).
     * The caller owns the bitmap and must recycle it.
     */
    suspend fun captureScreenshot(): Bitmap
}
