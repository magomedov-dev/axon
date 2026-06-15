package com.axon.agent.handlers

import com.axon.agent.rpc.ErrorCodes
import com.axon.agent.rpc.RpcContext
import com.axon.agent.rpc.RpcException
import com.axon.agent.rpc.RpcHandler
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * setEventStream — the per-connection tap for server-push events. The boolean
 * flag is the only subscription state the device keeps.
 */
object EventStreamHandler : RpcHandler {

    override suspend fun handle(params: JsonObject?, ctx: RpcContext): JsonElement {
        val enabled = params?.get("enabled")?.jsonPrimitive?.booleanOrNull
            ?: throw RpcException(ErrorCodes.INVALID_PARAMS, "missing boolean 'enabled'")
        ctx.connection.eventStream = enabled
        return buildJsonObject {
            put("success", true)
            put("enabled", enabled)
        }
    }
}
