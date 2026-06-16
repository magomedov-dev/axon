package com.axon.agent.tree

import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.axon.agent.core.Agent
import com.axon.agent.rpc.ErrorCodes
import com.axon.agent.rpc.RpcException

/**
 * Resolves the window root to operate on — a specific window by id, or the active
 * window when none is given. Shared by dumpHierarchy and nodeAction so the
 * active-vs-windowId logic and error codes live in one place. Call on the tree
 * thread; the caller owns (and recycles) the returned node.
 */
object WindowRoots {

    fun resolve(agent: Agent, windowId: Int?): AccessibilityNodeInfo {
        if (windowId == null) {
            return agent.rootNode()
                ?: throw RpcException(ErrorCodes.ACCESSIBILITY_DISABLED, "no active window root")
        }
        val windows = agent.windowInfos()
        val root = try {
            windows.firstOrNull { it.id == windowId }?.root
        } finally {
            windows.forEach(::recycleWindow)
        }
        return root ?: throw RpcException(
            ErrorCodes.WINDOW_NOT_FOUND,
            "no window with id $windowId (it may have closed, or has no root)"
        )
    }

    @Suppress("DEPRECATION")
    private fun recycleWindow(window: AccessibilityWindowInfo) {
        runCatching { window.recycle() }
    }
}
