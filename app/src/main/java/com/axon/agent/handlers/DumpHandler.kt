package com.axon.agent.handlers

import com.axon.agent.rpc.RpcContext
import com.axon.agent.rpc.RpcHandler
import com.axon.agent.tree.TreeWalker
import com.axon.agent.tree.WindowRoots
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import android.view.accessibility.AccessibilityNodeInfo

/**
 * dumpHierarchy — the first end-to-end method. Starts from a FRESH window root
 * (nothing cached between calls) and serializes the tree.
 *
 * result = the root node object, plus `screen` (state generation) and `package`
 * (foreground app), guaranteed present in every dump. A missing root (service off
 * or no foreground window) is a clean error, never a crash.
 *
 * Params: { maxDepth?: int, compress?: bool, windowId?: int }. With windowId the
 * dump targets that specific window (from getWindows); default is the active
 * window. All tree work runs on the single TreeDispatcher thread.
 */
object DumpHandler : RpcHandler {

    override suspend fun handle(params: JsonObject?, ctx: RpcContext): JsonElement {
        val maxDepth = params?.get("maxDepth")?.jsonPrimitive?.intOrNull ?: Int.MAX_VALUE
        val compress = params?.get("compress")?.jsonPrimitive?.booleanOrNull ?: false
        val windowId = params?.get("windowId")?.jsonPrimitive?.intOrNull

        return ctx.agent.tree.on {
            val root = WindowRoots.resolve(ctx.agent, windowId)
            try {
                val node = TreeWalker.walk(root, maxDepth, compress)
                buildJsonObject {
                    put("screen", ctx.agent.screen.value)
                    put("package", root.packageName?.toString())
                    node.forEach { (key, value) -> put(key, value) }
                }
            } finally {
                recycle(root)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun recycle(node: AccessibilityNodeInfo) {
        runCatching { node.recycle() }
    }
}
