package com.axon.agent.rpc

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Parses a raw text frame, routes it to a handler, and renders the reply.
 * The single funnel for `parse → validate → route → reply`; always echoes the
 * request id (or null when it couldn't be read) and never throws to the caller —
 * every failure becomes a structured error response.
 *
 * Returns the reply text to send, or null when the handler already wrote the
 * reply itself (a JSON-metadata + binary-frame pair, sent atomically here).
 */
class JsonRpcDispatcher(private val router: MethodRouter) {

    suspend fun dispatch(raw: String, ctx: RpcContext): String? {
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
            val result = handler.handle(params, ctx)
            val json = RpcMessages.success(id, result)
            val binary = ctx.binary
            if (binary != null) {
                // The id is encoded into the 4-byte binary-frame header, so it must
                // fit a uint32. Reject anything else with a clear error instead of
                // silently corrupting the correlation id.
                val frameId = uint32Id(id)
                    ?: return RpcMessages.error(
                        id,
                        ErrorCodes.INVALID_PARAMS,
                        "a binary response (screenshot) requires an integer id in [0, 4294967295]"
                    )
                // Metadata + binary frame must go out atomically (no interleaving).
                ctx.connection.writer.sendJsonThenBinary(json, frameId, binary)
                null
            } else {
                json
            }
        } catch (e: RpcException) {
            RpcMessages.error(id, e.code, e.message ?: e.code)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            RpcMessages.error(id, ErrorCodes.INTERNAL, e.message ?: "internal error")
        }
    }

    /**
     * The request id as a uint32 for the binary-frame header, or null if it can't
     * be represented (a string, a non-integer, or out of [0, 2^32-1]).
     */
    private fun uint32Id(id: JsonElement?): Long? {
        val primitive = id as? JsonPrimitive ?: return null
        if (primitive.isString) return null
        val value = primitive.longOrNull ?: return null
        return if (value in 0..0xFFFF_FFFFL) value else null
    }
}
