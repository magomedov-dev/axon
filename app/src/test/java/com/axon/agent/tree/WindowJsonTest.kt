package com.axon.agent.tree

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowJsonTest {

    private fun sample(root: kotlinx.serialization.json.JsonObject? = null) = WindowJson.window(
        windowId = 5, type = "application", layer = 3,
        active = true, focused = false,
        title = "App", packageName = "com.app",
        left = 0, top = 0, right = 1080, bottom = 2280,
        root = root,
    )

    @Test
    fun metadata_serialized() {
        val w = sample()
        assertEquals(5, w["windowId"]!!.jsonPrimitive.int)
        assertEquals("application", w["type"]!!.jsonPrimitive.content)
        assertEquals(3, w["layer"]!!.jsonPrimitive.int)
        assertTrue(w["active"]!!.jsonPrimitive.content.toBoolean())
        assertFalse(w["focused"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("com.app", w["package"]!!.jsonPrimitive.content)
        val b = w["bounds"]!!.jsonObject
        assertEquals(1080, b["right"]!!.jsonPrimitive.int)
        assertEquals(2280, b["bottom"]!!.jsonPrimitive.int)
    }

    @Test
    fun root_omittedWhenNull_includedWhenPresent() {
        assertFalse(sample(root = null).containsKey("root"))
        val tree = buildJsonObject { put("nodeId", 0) }
        val w = sample(root = tree)
        assertTrue(w.containsKey("root"))
        assertEquals(0, w["root"]!!.jsonObject["nodeId"]!!.jsonPrimitive.int)
    }

    @Test
    fun nullTitle_serializesAsJsonNull() {
        val w = WindowJson.window(1, "system", 0, false, false, null, null, 0, 0, 0, 0, null)
        assertEquals(JsonNull, w["title"])
        assertEquals(JsonNull, w["package"])
    }
}
