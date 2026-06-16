package com.axon.agent.node

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Finds all nodes matching `by`=`value` by exact equality, via a depth-first walk
 * from a fresh root. MUST run on the TreeDispatcher thread. Returns live nodes
 * (the caller acts on one and is responsible for recycling them).
 *
 * Exact match is used uniformly across all selectors for predictable semantics
 * (unlike findAccessibilityNodeInfosByText, which is substring + case-insensitive
 * and also matches content descriptions).
 */
object NodeFinder {

    fun findAll(
        root: AccessibilityNodeInfo,
        by: String,
        value: String,
        match: String,
    ): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()

        fun visit(node: AccessibilityNodeInfo) {
            if (matches(node, by, value, match)) out.add(node)
            val count = node.childCount
            for (i in 0 until count) {
                val child = node.getChild(i) ?: continue
                visit(child)
            }
        }

        visit(root)
        return out
    }

    private fun matches(node: AccessibilityNodeInfo, by: String, value: String, match: String): Boolean {
        val actual = when (by) {
            "resourceId" -> node.viewIdResourceName
            "text" -> node.text?.toString()
            "class" -> node.className?.toString()
            "contentDesc" -> node.contentDescription?.toString()
            else -> return false
        }
        return NodeMatch.matches(actual, value, match)
    }
}
