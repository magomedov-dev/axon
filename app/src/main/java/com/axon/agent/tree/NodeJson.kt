package com.axon.agent.tree

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Pure builder for one node's JSON. Kept free of Android types so the wire shape
 * — bounds, computed center, and the `compress` rules — is unit-testable.
 *
 * `compress` drops the (recomputable) center and any empty children array to save
 * traffic; everything else is always present (null when absent) for a stable schema.
 */
object NodeJson {

    @Suppress("LongParameterList")
    fun node(
        id: Int,
        parentId: Int?,
        className: String?,
        text: String?,
        resourceId: String?,
        contentDesc: String?,
        clickable: Boolean,
        enabled: Boolean,
        focused: Boolean,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        children: List<JsonObject>,
        compress: Boolean,
    ): JsonObject = buildJsonObject {
        put("nodeId", id)
        put("parentId", parentId)
        put("class", className)
        put("text", text)
        put("resourceId", resourceId)
        put("contentDesc", contentDesc)
        put("clickable", clickable)
        put("enabled", enabled)
        put("focused", focused)
        putJsonObject("bounds") {
            put("left", left)
            put("top", top)
            put("right", right)
            put("bottom", bottom)
        }
        if (!compress) {
            putJsonObject("center") {
                put("x", (left + right) / 2)
                put("y", (top + bottom) / 2)
            }
        }
        // compress: omit empty children; otherwise always present (possibly []).
        if (!compress || children.isNotEmpty()) {
            put("children", JsonArray(children))
        }
    }
}
