package com.axon.agent.core

import kotlinx.coroutines.CoroutineScope

/**
 * The capabilities RPC handlers need from the host, abstracted away from the
 * Android AccessibilityService so handlers and the dispatcher stay unit-testable
 * off-device. [AutomationAccessibilityService] is the production implementation.
 *
 * The surface grows per stage (root node access, screenshots, event broadcast).
 */
interface Agent {
    /** Service-scoped coroutine scope; cancelled when the service dies. */
    val scope: CoroutineScope

    /** Single-thread confinement for all AccessibilityNodeInfo work. */
    val tree: TreeDispatcher
}
