package com.axon.agent.handlers

import com.axon.agent.rpc.ErrorCodes
import com.axon.agent.rpc.RpcContext
import com.axon.agent.rpc.RpcException
import com.axon.agent.rpc.RpcHandler
import com.axon.agent.tree.TreeWalker
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import android.view.accessibility.AccessibilityNodeInfo

/**
 * dumpHierarchy — the first end-to-end method. Starts from a FRESH
 * getRootInActiveWindow() (nothing cached between calls) and serializes the tree.
 *
 * result = the root node object, plus `screen` (state generation) and `package`
 * (foreground app), guaranteed present in every dump. A missing root (service off
 * or no foreground window) is a clean error, never a crash.
 *
 * Params: { maxDepth?: int, compress?: bool }. All tree work runs on the
 * single TreeDispatcher thread.
 */
object DumpHandler : RpcHandler {

    override suspend fun handle(params: JsonObject?, ctx: RpcContext): JsonElement {
        val maxDepth = params?.get("maxDepth")?.jsonPrimitive?.intOrNull ?: Int.MAX_VALUE
        val compress = params?.get("compress")?.jsonPrimitive?.booleanOrNull ?: false

        return ctx.agent.tree.on {
            val root = ctx.agent.rootNode()
                ?: throw RpcException(
                    ErrorCodes.ACCESSIBILITY_DISABLED,
                    "no active window root (accessibility off or no foreground window)"
                )
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
