package com.axon.agent.handlers

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.axon.agent.rpc.RpcContext
import com.axon.agent.rpc.RpcHandler
import com.axon.agent.tree.TreeWalker
import com.axon.agent.tree.WindowJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * getWindows — enumerate ALL interactive windows (application, IME, system,
 * dialogs, overlays, split-screen), topmost first. Unlike dumpHierarchy (which is
 * the active window only), this covers the whole window stack.
 *
 * Params: { includeTree?: bool (default false), maxDepth?: int, compress?: bool }.
 * With includeTree each window carries its node tree under `root` (maxDepth/compress
 * apply per window). Without it, only window metadata is returned (cheap).
 */
object GetWindowsHandler : RpcHandler {

    override suspend fun handle(params: JsonObject?, ctx: RpcContext): JsonElement {
        val includeTree = params?.get("includeTree")?.jsonPrimitive?.booleanOrNull ?: false
        val maxDepth = params?.get("maxDepth")?.jsonPrimitive?.intOrNull ?: Int.MAX_VALUE
        val compress = params?.get("compress")?.jsonPrimitive?.booleanOrNull ?: false

        return ctx.agent.tree.on {
            val windows = ctx.agent.windowInfos().sortedByDescending { it.layer }
            try {
                val array = JsonArray(windows.map { windowToJson(it, includeTree, maxDepth, compress) })
                buildJsonObject {
                    put("screen", ctx.agent.screen.value)
                    put("windows", array)
                }
            } finally {
                windows.forEach(::recycleWindow)
            }
        }
    }

    private fun windowToJson(
        window: AccessibilityWindowInfo,
        includeTree: Boolean,
        maxDepth: Int,
        compress: Boolean,
    ): JsonObject {
        val rect = Rect()
        window.getBoundsInScreen(rect)
        val root = window.root
        val tree = if (includeTree && root != null) TreeWalker.walk(root, maxDepth, compress) else null
        val packageName = root?.packageName?.toString()
        if (root != null) recycleNode(root)

        return WindowJson.window(
            windowId = window.id,
            type = typeName(window.type),
            layer = window.layer,
            active = window.isActive,
            focused = window.isFocused,
            title = window.title?.toString(),
            packageName = packageName,
            left = rect.left,
            top = rect.top,
            right = rect.right,
            bottom = rect.bottom,
            root = tree,
        )
    }

    private fun typeName(type: Int): String = when (type) {
        AccessibilityWindowInfo.TYPE_APPLICATION -> "application"
        AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "inputMethod"
        AccessibilityWindowInfo.TYPE_SYSTEM -> "system"
        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "accessibilityOverlay"
        AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "splitScreenDivider"
        AccessibilityWindowInfo.TYPE_MAGNIFICATION_OVERLAY -> "magnification"
        else -> "unknown"
    }

    @Suppress("DEPRECATION")
    private fun recycleNode(node: AccessibilityNodeInfo) {
        runCatching { node.recycle() }
    }

    @Suppress("DEPRECATION")
    private fun recycleWindow(window: AccessibilityWindowInfo) {
        runCatching { window.recycle() }
    }
}
