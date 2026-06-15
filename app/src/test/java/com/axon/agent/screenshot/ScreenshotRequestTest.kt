package com.axon.agent.screenshot

import com.axon.agent.rpc.ErrorCodes
import com.axon.agent.rpc.RpcException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ScreenshotRequestTest {

    private fun parse(raw: String) =
        ScreenshotRequest.parse(Json.parseToJsonElement(raw).jsonObject as JsonObject)

    private fun assertInvalid(raw: String) {
        val e = assertThrows(RpcException::class.java) { parse(raw) }
        assertEquals(ErrorCodes.INVALID_PARAMS, e.code)
    }

    @Test
    fun defaults_jpegQuality80() {
        val r = ScreenshotRequest.parse(null)
        assertEquals("jpeg", r.format)
        assertEquals(80, r.quality)
    }

    @Test
    fun png_parsed() {
        assertEquals("png", parse("""{"format":"png"}""").format)
    }

    @Test
    fun explicitQuality_parsed() {
        assertEquals(55, parse("""{"format":"jpeg","quality":55}""").quality)
    }

    @Test fun invalidFormat_invalid() = assertInvalid("""{"format":"gif"}""")
    @Test fun qualityTooHigh_invalid() = assertInvalid("""{"quality":101}""")
    @Test fun qualityNegative_invalid() = assertInvalid("""{"quality":-1}""")
}
