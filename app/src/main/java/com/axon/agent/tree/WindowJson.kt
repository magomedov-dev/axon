package com.axon.agent.tree

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Pure builder for one window's JSON (getWindows). Android-free so the wire shape
 * is unit-testable; the caller resolves the Android bits (type name, bounds, root
 * tree) and passes primitives here. `root` is present only when a node tree was
 * requested and available.
 */
object WindowJson {

    @Suppress("LongParameterList")
    fun window(
        windowId: Int,
        type: String,
        layer: Int,
        active: Boolean,
        focused: Boolean,
        title: String?,
        packageName: String?,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        root: JsonObject?,
    ): JsonObject = buildJsonObject {
        put("windowId", windowId)
        put("type", type)
        put("layer", layer)
        put("active", active)
        put("focused", focused)
        put("title", title)
        put("package", packageName)
        putJsonObject("bounds") {
            put("left", left)
            put("top", top)
            put("right", right)
            put("bottom", bottom)
        }
        if (root != null) put("root", root)
    }
}
