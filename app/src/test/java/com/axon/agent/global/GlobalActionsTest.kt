package com.axon.agent.global

import com.axon.agent.rpc.ErrorCodes
import com.axon.agent.rpc.RpcException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GlobalActionsTest {

    private fun obj(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject

    @Test
    fun validate_acceptsEveryKnownAction() {
        for (action in GlobalActions.ACTIONS) {
            assertEquals(action, GlobalActions.validate(obj("""{"action":"$action"}""")))
        }
    }

    @Test
    fun validate_missingAction_invalid() {
        val e = assertThrows(RpcException::class.java) { GlobalActions.validate(obj("{}")) }
        assertEquals(ErrorCodes.INVALID_PARAMS, e.code)
    }

    @Test
    fun validate_unknownAction_invalid() {
        val e = assertThrows(RpcException::class.java) {
            GlobalActions.validate(obj("""{"action":"selfDestruct"}"""))
        }
        assertEquals(ErrorCodes.INVALID_PARAMS, e.code)
    }
}
