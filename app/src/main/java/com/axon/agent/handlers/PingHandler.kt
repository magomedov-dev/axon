package com.axon.agent.handlers

import com.axon.agent.rpc.RpcContext
import com.axon.agent.rpc.RpcHandler
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * App-level heartbeat. A reply proves the whole pipeline is alive — WS read
 * thread, coroutine dispatch, and serialized write — not just that the TCP
 * socket is open. The PC client uses this to detect a wedged/dead service.
 */
object PingHandler : RpcHandler {
    override suspend fun handle(params: JsonObject?, ctx: RpcContext): JsonElement =
        buildJsonObject {
            put("pong", true)
            put("ts", System.currentTimeMillis())
        }
}
