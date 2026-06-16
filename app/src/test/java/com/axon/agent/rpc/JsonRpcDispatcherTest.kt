package com.axon.agent.rpc

import com.axon.agent.core.Agent
import com.axon.agent.core.TreeDispatcher
import com.axon.agent.handlers.PingHandler
import com.axon.agent.server.ConnectionState
import com.axon.agent.server.Sender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonRpcDispatcherTest {

    private val dispatcher = JsonRpcDispatcher(MethodRouter(mapOf("ping" to PingHandler)))
    private val ctx = RpcContext(ConnectionState(1, NoopSender), FakeAgent())

    private suspend fun dispatch(raw: String): JsonObject =
        RpcMessages.json.parseToJsonElement(dispatcher.dispatch(raw, ctx)!!).jsonObject

    @Test
    fun ping_returnsPongAndEchoesId() = runTest {
        val resp = dispatch("""{"id":1,"method":"ping"}""")
        assertEquals(1, resp["id"]!!.jsonPrimitive.int)
        val result = resp["result"]!!.jsonObject
        assertTrue(result["pong"]!!.jsonPrimitive.boolean)
        assertTrue(result["ts"]!!.jsonPrimitive.long > 0)
    }

    @Test
    fun invalidJson_givesParseErrorWithNullId() = runTest {
        val resp = dispatch("{not valid")
        assertEquals(JsonNull, resp["id"])
        assertEquals(ErrorCodes.PARSE_ERROR, resp["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun nonObjectRequest_givesInvalidRequest() = runTest {
        val resp = dispatch("5")
        assertEquals(ErrorCodes.INVALID_REQUEST, resp["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun missingMethod_givesInvalidRequestWithEchoedId() = runTest {
        val resp = dispatch("""{"id":2}""")
        assertEquals(2, resp["id"]!!.jsonPrimitive.int)
        assertEquals(ErrorCodes.INVALID_REQUEST, resp["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun unknownMethod_givesMethodNotFound() = runTest {
        val resp = dispatch("""{"id":3,"method":"doesNotExist"}""")
        assertEquals(3, resp["id"]!!.jsonPrimitive.int)
        assertEquals(ErrorCodes.METHOD_NOT_FOUND, resp["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun handlerThatThrows_becomesStructuredError() = runTest {
        val d = JsonRpcDispatcher(MethodRouter(mapOf("boom" to object : RpcHandler {
            override suspend fun handle(params: JsonObject?, ctx: RpcContext) =
                throw RpcException(ErrorCodes.INVALID_PARAMS, "bad params")
        })))
        val resp = RpcMessages.json
            .parseToJsonElement(d.dispatch("""{"id":9,"method":"boom"}""", ctx)!!).jsonObject
        val err = resp["error"]!!.jsonObject
        assertEquals(ErrorCodes.INVALID_PARAMS, err["code"]!!.jsonPrimitive.content)
        assertEquals("bad params", err["message"]!!.jsonPrimitive.content)
    }

    @Test
    fun binaryResponse_withNonIntegerId_isRejected() = runTest {
        // string id can't be encoded into the uint32 binary-frame header -> error,
        // and no binary frame is sent.
        val resp = RpcMessages.json
            .parseToJsonElement(JsonRpcDispatcher(binaryRouter).dispatch("""{"id":"abc","method":"shot"}""", ctx)!!)
            .jsonObject
        assertEquals(ErrorCodes.INVALID_PARAMS, resp["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun binaryResponse_withIntegerId_isSentDirectly() = runTest {
        // valid uint32 id -> handler wrote the reply itself (text + binary) -> null
        assertNull(JsonRpcDispatcher(binaryRouter).dispatch("""{"id":7,"method":"shot"}""", ctx))
    }

    private val binaryRouter = MethodRouter(mapOf("shot" to object : RpcHandler {
        override suspend fun handle(params: JsonObject?, ctx: RpcContext): JsonElement {
            ctx.binary = byteArrayOf(1, 2, 3)
            return buildJsonObject { put("ok", true) }
        }
    }))

    // ---- fakes ------------------------------------------------------------
    private object NoopSender : Sender {
        override val isOpen = true
        override fun sendText(text: String) {}
        override fun sendBinary(bytes: ByteArray) {}
    }

    private class FakeAgent : Agent {
        override val scope = CoroutineScope(Job())
        override val tree: TreeDispatcher get() = error("tree must not be used in this test")
        override val screen = com.axon.agent.core.ScreenCounter()
        override fun rootNode() = null
        override suspend fun performGesture(gesture: android.accessibilityservice.GestureDescription) = false
        override fun performGlobalAction(action: Int) = false
        override suspend fun captureScreenshot(): android.graphics.Bitmap = error("no screenshot in test")
        init { assertNotNull(scope) }
    }
}
