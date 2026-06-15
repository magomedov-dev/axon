package com.axon.agent.handlers

import android.accessibilityservice.AccessibilityService
import com.axon.agent.global.GlobalActions
import com.axon.agent.rpc.ErrorCodes
import com.axon.agent.rpc.RpcContext
import com.axon.agent.rpc.RpcException
import com.axon.agent.rpc.RpcHandler
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * globalAction — system-level actions via performGlobalAction. One key→constant
 * table; unknown actions are rejected in [GlobalActions.validate] before any
 * device call. Returns `{ "success": <performGlobalAction result> }`.
 */
object GlobalActionHandler : RpcHandler {

    override suspend fun handle(params: JsonObject?, ctx: RpcContext): JsonElement {
        val action = GlobalActions.validate(params)
        val ok = ctx.agent.performGlobalAction(idFor(action))
        return buildJsonObject { put("success", ok) }
    }

    private fun idFor(action: String): Int = when (action) {
        GlobalActions.BACK -> AccessibilityService.GLOBAL_ACTION_BACK
        GlobalActions.HOME -> AccessibilityService.GLOBAL_ACTION_HOME
        GlobalActions.RECENTS -> AccessibilityService.GLOBAL_ACTION_RECENTS
        GlobalActions.NOTIFICATIONS -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
        GlobalActions.QUICK_SETTINGS -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
        GlobalActions.POWER_DIALOG -> AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
        GlobalActions.LOCK_SCREEN -> AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
        // validate() already guarantees a known key.
        else -> throw RpcException(ErrorCodes.INTERNAL, "unmapped global action: '$action'")
    }
}
