package com.axon.agent.events

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Pure builders for server-push event frames (no `id` — that's how the client
 * tells events from responses). JsonElement.toString() emits valid JSON.
 */
object EventMessages {

    fun screenChanged(screen: Int, packageName: String?): String =
        buildJsonObject {
            put("event", "screenChanged")
            put("screen", screen)
            put("package", packageName)
        }.toString()

    fun toast(text: String, packageName: String?): String =
        buildJsonObject {
            put("event", "toast")
            put("text", text)
            put("package", packageName)
        }.toString()
}
