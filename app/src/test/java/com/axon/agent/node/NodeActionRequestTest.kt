package com.axon.agent.node

import com.axon.agent.rpc.ErrorCodes
import com.axon.agent.rpc.RpcException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class NodeActionRequestTest {

    private fun obj(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject
    private fun parse(raw: String) = NodeActionRequest.parse(obj(raw))

    private fun assertInvalid(raw: String) {
        val e = assertThrows(RpcException::class.java) { parse(raw) }
        assertEquals(ErrorCodes.INVALID_PARAMS, e.code)
    }

    @Test
    fun click_parsesMinimal() {
        val r = parse("""{"by":"resourceId","value":"a:id/b","action":"click"}""")
        assertEquals("resourceId", r.by)
        assertEquals("a:id/b", r.value)
        assertEquals("click", r.action)
        assertNull(r.index)
    }

    @Test
    fun setText_requiresText() {
        val r = parse("""{"by":"text","value":"x","action":"setText","text":"hi"}""")
        assertEquals("hi", r.text)
        assertInvalid("""{"by":"text","value":"x","action":"setText"}""")
    }

    @Test
    fun setSelection_requiresStartAndEnd() {
        val r = parse("""{"by":"text","value":"x","action":"setSelection","start":1,"end":3}""")
        assertEquals(1, r.start)
        assertEquals(3, r.end)
        assertInvalid("""{"by":"text","value":"x","action":"setSelection","start":1}""")
    }

    @Test
    fun index_parsedAndValidated() {
        assertEquals(2, parse("""{"by":"class","value":"X","action":"click","index":2}""").index)
        assertInvalid("""{"by":"class","value":"X","action":"click","index":-1}""")
    }

    @Test
    fun match_defaultsToExact() {
        assertEquals(NodeMatch.EXACT, parse("""{"by":"text","value":"x","action":"click"}""").match)
    }

    @Test
    fun match_containsAndRegexParsed() {
        assertEquals(NodeMatch.CONTAINS, parse("""{"by":"text","value":"x","action":"click","match":"contains"}""").match)
        assertEquals(NodeMatch.REGEX, parse("""{"by":"text","value":"a.*","action":"click","match":"regex"}""").match)
    }

    @Test fun unknownMatch_invalid() = assertInvalid("""{"by":"text","value":"x","action":"click","match":"fuzzy"}""")

    @Test
    fun invalidRegexValue_invalid() =
        assertInvalid("""{"by":"text","value":"(unclosed","action":"click","match":"regex"}""")

    @Test fun missingBy_invalid() = assertInvalid("""{"value":"x","action":"click"}""")
    @Test fun invalidBy_invalid() = assertInvalid("""{"by":"nope","value":"x","action":"click"}""")
    @Test fun missingValue_invalid() = assertInvalid("""{"by":"text","action":"click"}""")
    @Test fun missingAction_invalid() = assertInvalid("""{"by":"text","value":"x"}""")
    @Test fun unknownAction_invalid() = assertInvalid("""{"by":"text","value":"x","action":"teleport"}""")
}
