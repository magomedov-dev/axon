package com.axon.agent.tree

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeJsonTest {

    private fun leaf(compress: Boolean): JsonObject = NodeJson.node(
        id = 0, parentId = null,
        className = "android.widget.Button", text = "Войти",
        resourceId = "com.app:id/login", contentDesc = null,
        clickable = true, enabled = true, focused = false,
        left = 420, top = 1800, right = 660, bottom = 1920,
        children = emptyList(), compress = compress,
    )

    @Test
    fun center_isMidpointOfBounds() {
        val n = leaf(compress = false)
        val center = n["center"]!!.jsonObject
        assertEquals(540, center["x"]!!.jsonPrimitive.int) // (420+660)/2
        assertEquals(1860, center["y"]!!.jsonPrimitive.int) // (1800+1920)/2
    }

    @Test
    fun bounds_isNumericObject() {
        val b = leaf(compress = false)["bounds"]!!.jsonObject
        assertEquals(420, b["left"]!!.jsonPrimitive.int)
        assertEquals(1800, b["top"]!!.jsonPrimitive.int)
        assertEquals(660, b["right"]!!.jsonPrimitive.int)
        assertEquals(1920, b["bottom"]!!.jsonPrimitive.int)
    }

    @Test
    fun nullParentAndContentDesc_serializeAsJsonNull() {
        val n = leaf(compress = false)
        assertEquals(JsonNull, n["parentId"])
        assertEquals(JsonNull, n["contentDesc"])
    }

    @Test
    fun nonCompress_includesCenterAndEmptyChildrenArray() {
        val n = leaf(compress = false)
        assertTrue(n.containsKey("center"))
        assertTrue(n.containsKey("children"))
        assertEquals(0, n["children"]!!.jsonArray.size)
    }

    @Test
    fun compress_dropsCenterAndEmptyChildren() {
        val n = leaf(compress = true)
        assertFalse(n.containsKey("center"))
        assertFalse(n.containsKey("children"))
        assertNull(n["center"])
    }

    @Test
    fun compress_keepsNonEmptyChildren() {
        val child = leaf(compress = true)
        val parent = NodeJson.node(
            id = 1, parentId = 0, className = "ViewGroup", text = null,
            resourceId = null, contentDesc = null,
            clickable = false, enabled = true, focused = false,
            left = 0, top = 0, right = 100, bottom = 100,
            children = listOf(child), compress = true,
        )
        assertTrue(parent.containsKey("children"))
        assertEquals(1, parent["children"]!!.jsonArray.size)
    }
}
