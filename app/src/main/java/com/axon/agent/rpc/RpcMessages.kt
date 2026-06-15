package com.axon.agent.rpc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Pure builders for the JSON-RPC wire format. No I/O, no Android — unit-testable.
 *
 *   success: { "id": <id>, "result": <result> }
 *   error:   { "id": <id>, "error": { "code": <code>, "message": <msg> } }
 *
 * The incoming `id` is echoed back verbatim (or null when it couldn't be read).
 * JsonElement.toString() already emits valid, compact JSON.
 */
object RpcMessages {

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
    }

    fun success(id: JsonElement?, result: JsonElement): String =
        buildJsonObject {
            put("id", id ?: JsonNull)
            put("result", result)
        }.toString()

    fun error(id: JsonElement?, code: String, message: String): String =
        buildJsonObject {
            put("id", id ?: JsonNull)
            putJsonObject("error") {
                put("code", code)
                put("message", message)
            }
        }.toString()
}
