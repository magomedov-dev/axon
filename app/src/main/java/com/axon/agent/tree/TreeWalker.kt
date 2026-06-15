package com.axon.agent.tree

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.serialization.json.JsonObject

/**
 * Walks a live AccessibilityNodeInfo tree into JSON. MUST be invoked on the
 * TreeDispatcher thread — every getChild() is an IPC and the nodes don't tolerate
 * concurrent access.
 *
 * Traversal is pre-order: nodeId is a running counter assigned on first visit
 * (root = 0), parentId points at the enclosing node (null for root). Child nodes
 * are recycled as we go; the root is owned by the caller.
 */
object TreeWalker {

    fun walk(root: AccessibilityNodeInfo, maxDepth: Int, compress: Boolean): JsonObject {
        var counter = 0

        fun visit(node: AccessibilityNodeInfo, parentId: Int?, depth: Int): JsonObject {
            val id = counter++
            val rect = Rect()
            node.getBoundsInScreen(rect)

            val children = ArrayList<JsonObject>()
            if (depth < maxDepth) {
                val count = node.childCount
                for (i in 0 until count) {
                    val child = node.getChild(i) ?: continue
                    children.add(visit(child, id, depth + 1))
                    recycle(child)
                }
            }

            return NodeJson.node(
                id = id,
                parentId = parentId,
                className = node.className?.toString(),
                text = node.text?.toString(),
                resourceId = node.viewIdResourceName,
                contentDesc = node.contentDescription?.toString(),
                clickable = node.isClickable,
                enabled = node.isEnabled,
                focused = node.isFocused,
                left = rect.left,
                top = rect.top,
                right = rect.right,
                bottom = rect.bottom,
                children = children,
                compress = compress,
            )
        }

        return visit(root, null, 0)
    }

    @Suppress("DEPRECATION") // no-op on API 33+, still frees memory on 30–32
    private fun recycle(node: AccessibilityNodeInfo) {
        runCatching { node.recycle() }
    }
}
