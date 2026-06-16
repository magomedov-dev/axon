package com.axon.agent.handlers

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import com.axon.agent.node.NodeActionRequest
import com.axon.agent.node.NodeActions
import com.axon.agent.node.NodeFinder
import com.axon.agent.rpc.ErrorCodes
import com.axon.agent.rpc.RpcContext
import com.axon.agent.rpc.RpcException
import com.axon.agent.rpc.RpcHandler
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * nodeAction — stateless within the call: find a node from a FRESH root by
 * criteria and performAction on it. The node never outlives the RPC.
 *
 * Errors are deliberately distinct so the PC can react: NODE_NOT_FOUND,
 * AMBIGUOUS_MATCH (refine or pass index), NOT_EDITABLE, ACTION_NOT_SUPPORTED,
 * STALE (performAction returned false). No silent retries — that's the PC's job.
 */
object NodeActionHandler : RpcHandler {

    override suspend fun handle(params: JsonObject?, ctx: RpcContext): JsonElement {
        val req = NodeActionRequest.parse(params)

        return ctx.agent.tree.on {
            val root = ctx.agent.rootNode()
                ?: throw RpcException(ErrorCodes.ACCESSIBILITY_DISABLED, "no active window root")
            val matches = NodeFinder.findAll(root, req.by, req.value, req.match)
            try {
                if (matches.isEmpty()) {
                    throw RpcException(ErrorCodes.NODE_NOT_FOUND, "no node matches ${req.by}='${req.value}'")
                }
                val target = select(matches, req)
                val success = perform(target, req)
                buildJsonObject { put("success", success) }
            } finally {
                matches.forEach(::recycle)
                recycle(root)
            }
        }
    }

    private fun select(matches: List<AccessibilityNodeInfo>, req: NodeActionRequest): AccessibilityNodeInfo =
        when {
            req.index != null -> matches.getOrNull(req.index)
                ?: throw RpcException(
                    ErrorCodes.INVALID_PARAMS,
                    "index ${req.index} out of range (${matches.size} matches)"
                )
            matches.size == 1 -> matches[0]
            else -> throw RpcException(
                ErrorCodes.AMBIGUOUS_MATCH,
                "${matches.size} nodes match ${req.by}='${req.value}'; pass 'index' to choose one"
            )
        }

    private fun perform(target: AccessibilityNodeInfo, req: NodeActionRequest): Boolean {
        if (req.action in NodeActions.NEEDS_EDITABLE && !target.isEditable) {
            throw RpcException(ErrorCodes.NOT_EDITABLE, "node is not editable for '${req.action}'")
        }

        val actionId = actionId(req.action)
        if (target.actionList.none { it.id == actionId }) {
            throw RpcException(ErrorCodes.ACTION_NOT_SUPPORTED, "node does not support '${req.action}'")
        }

        val args = argsFor(req)
        val ok = if (args != null) target.performAction(actionId, args) else target.performAction(actionId)
        if (!ok) {
            throw RpcException(ErrorCodes.STALE, "performAction('${req.action}') returned false (node went stale)")
        }
        return true
    }

    private fun actionId(action: String): Int = when (action) {
        NodeActions.CLICK -> AccessibilityAction.ACTION_CLICK.id
        NodeActions.LONG_CLICK -> AccessibilityAction.ACTION_LONG_CLICK.id
        NodeActions.SET_TEXT, NodeActions.CLEAR -> AccessibilityAction.ACTION_SET_TEXT.id
        NodeActions.FOCUS -> AccessibilityAction.ACTION_FOCUS.id
        NodeActions.CLEAR_FOCUS -> AccessibilityAction.ACTION_CLEAR_FOCUS.id
        NodeActions.SELECT -> AccessibilityAction.ACTION_SELECT.id
        NodeActions.SET_SELECTION -> AccessibilityAction.ACTION_SET_SELECTION.id
        NodeActions.SCROLL_FORWARD -> AccessibilityAction.ACTION_SCROLL_FORWARD.id
        NodeActions.SCROLL_BACKWARD -> AccessibilityAction.ACTION_SCROLL_BACKWARD.id
        else -> throw RpcException(ErrorCodes.INVALID_PARAMS, "unknown action: '$action'")
    }

    private fun argsFor(req: NodeActionRequest): Bundle? = when (req.action) {
        NodeActions.SET_TEXT -> Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, req.text)
        }
        NodeActions.CLEAR -> Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
        }
        NodeActions.SET_SELECTION -> Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, req.start!!)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, req.end!!)
        }
        else -> null
    }

    @Suppress("DEPRECATION")
    private fun recycle(node: AccessibilityNodeInfo) {
        runCatching { node.recycle() }
    }
}
