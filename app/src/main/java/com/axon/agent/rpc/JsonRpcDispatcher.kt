package com.axon.agent.rpc

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Parses a raw text frame, routes it to a handler, and renders the reply string.
 * The single funnel for `parse → validate → route → reply`; always echoes the
 * request id (or null when it couldn't be read) and never throws to the caller —
 * every failure becomes a structured error response.
 */
class JsonRpcDispatcher(private val router: MethodRouter) {

    suspend fun dispatch(raw: String, ctx: RpcContext): String {
        val root: JsonElement = try {
            RpcMessages.json.parseToJsonElement(raw)
        } catch (e: Exception) {
            return RpcMessages.error(null, ErrorCodes.PARSE_ERROR, "invalid JSON: ${e.message}")
        }

        val obj = root as? JsonObject
            ?: return RpcMessages.error(null, ErrorCodes.INVALID_REQUEST, "request must be a JSON object")

        val id = obj["id"]
        val methodPrim = obj["method"] as? JsonPrimitive
        val method = methodPrim?.takeIf { it.isString }?.content
            ?: return RpcMessages.error(id, ErrorCodes.INVALID_REQUEST, "missing or non-string 'method'")

        val params = obj["params"] as? JsonObject
        val handler = router.handler(method)
            ?: return RpcMessages.error(id, ErrorCodes.METHOD_NOT_FOUND, "unknown method: $method")

        return try {
            RpcMessages.success(id, handler.handle(params, ctx))
        } catch (e: RpcException) {
            RpcMessages.error(id, e.code, e.message ?: e.code)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            RpcMessages.error(id, ErrorCodes.INTERNAL, e.message ?: "internal error")
        }
    }
}
