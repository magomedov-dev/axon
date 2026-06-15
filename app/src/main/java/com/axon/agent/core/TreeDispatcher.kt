package com.axon.agent.core

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withContext
import java.io.Closeable

/**
 * Single-thread confinement for all AccessibilityNodeInfo work.
 *
 * `getRootInActiveWindow()`, tree traversal and node search must never run
 * concurrently — AccessibilityNodeInfo does not tolerate concurrent access and
 * each getChild() is an IPC. Every tree operation is funnelled through this one
 * thread via [on], regardless of which coroutine/connection requested it.
 */
@OptIn(DelicateCoroutinesApi::class)
class TreeDispatcher : Closeable {

    private val context = newSingleThreadContext("axon-tree")

    /** Run [block] on the dedicated tree thread and return its result. */
    suspend fun <T> on(block: suspend () -> T): T = withContext(context) { block() }

    override fun close() = context.close()
}
