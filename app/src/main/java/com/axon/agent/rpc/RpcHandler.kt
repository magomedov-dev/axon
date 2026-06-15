package com.axon.agent.rpc

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * One RPC method. Returns the `result` payload on success, or throws
 * [RpcException] for a well-formed error. [params] is the raw request `params`
 * object (null if absent); each handler validates its own shape.
 */
interface RpcHandler {
    suspend fun handle(params: JsonObject?, ctx: RpcContext): JsonElement
}
