package com.axon.agent.global

import com.axon.agent.rpc.ErrorCodes
import com.axon.agent.rpc.RpcException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The one global-action table (keys only — pure, no Android). The key→GLOBAL_ACTION_*
 * id mapping lives next to performGlobalAction in GlobalActionHandler.
 */
object GlobalActions {
    const val BACK = "back"
    const val HOME = "home"
    const val RECENTS = "recents"
    const val NOTIFICATIONS = "notifications"
    const val QUICK_SETTINGS = "quickSettings"
    const val POWER_DIALOG = "powerDialog"
    const val LOCK_SCREEN = "lockScreen"

    val ACTIONS = setOf(
        BACK, HOME, RECENTS, NOTIFICATIONS, QUICK_SETTINGS, POWER_DIALOG, LOCK_SCREEN,
    )

    /** Validate params and return the action key, or throw INVALID_PARAMS. */
    fun validate(params: JsonObject?): String {
        val action = params?.get("action")?.jsonPrimitive?.contentOrNull
            ?: throw RpcException(ErrorCodes.INVALID_PARAMS, "missing 'action'")
        if (action !in ACTIONS) {
            throw RpcException(ErrorCodes.INVALID_PARAMS, "unknown global action: '$action' (expected one of $ACTIONS)")
        }
        return action
    }
}
