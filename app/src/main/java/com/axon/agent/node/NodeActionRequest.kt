package com.axon.agent.node

import com.axon.agent.rpc.ErrorCodes
import com.axon.agent.rpc.RpcException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parsed, validated `nodeAction` request. Pure — all param validation happens
 * here so each action's required extra fields (setText→text, setSelection→
 * start/end) are checked before any device work, and are unit-testable.
 */
data class NodeActionRequest(
    val by: String,
    val value: String,
    val match: String,
    val action: String,
    val index: Int?,
    val text: String?,
    val start: Int?,
    val end: Int?,
    /** Search within this specific window; null = the active window. */
    val windowId: Int?,
) {
    companion object {
        fun parse(params: JsonObject?): NodeActionRequest {
            val obj = params ?: bad("missing params")

            val by = obj["by"]?.jsonPrimitive?.contentOrNull ?: bad("missing 'by'")
            if (by !in NodeActions.BY) bad("invalid 'by': '$by' (expected one of ${NodeActions.BY})")

            val value = obj["value"]?.jsonPrimitive?.contentOrNull ?: bad("missing 'value'")

            val match = obj["match"]?.jsonPrimitive?.contentOrNull ?: NodeMatch.EXACT
            if (match !in NodeMatch.MODES) bad("invalid 'match': '$match' (expected one of ${NodeMatch.MODES})")
            if (match == NodeMatch.REGEX) {
                runCatching { Regex(value) }.onFailure { bad("invalid regex 'value': ${it.message}") }
            }

            val action = obj["action"]?.jsonPrimitive?.contentOrNull ?: bad("missing 'action'")
            if (action !in NodeActions.ACTIONS) bad("unknown action: '$action'")

            val index = obj["index"]?.jsonPrimitive?.intOrNull
            if (index != null && index < 0) bad("'index' must be >= 0")

            val text = obj["text"]?.jsonPrimitive?.contentOrNull
            if (action == NodeActions.SET_TEXT && text == null) bad("action 'setText' requires 'text'")

            val start = obj["start"]?.jsonPrimitive?.intOrNull
            val end = obj["end"]?.jsonPrimitive?.intOrNull
            if (action == NodeActions.SET_SELECTION && (start == null || end == null)) {
                bad("action 'setSelection' requires 'start' and 'end'")
            }

            val windowId = obj["windowId"]?.jsonPrimitive?.intOrNull

            return NodeActionRequest(by, value, match, action, index, text, start, end, windowId)
        }

        private fun bad(message: String): Nothing =
            throw RpcException(ErrorCodes.INVALID_PARAMS, message)
    }
}
