package com.axon.agent.gesture

import com.axon.agent.rpc.ErrorCodes
import com.axon.agent.rpc.RpcException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GestureSpecTest {

    private val maxStrokes = 10
    private val maxDuration = 60_000L

    private fun obj(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject
    private fun parse(raw: String): Gesture = GestureSpec.parse(obj(raw), maxStrokes, maxDuration)

    private fun assertInvalid(raw: String) {
        val e = assertThrows(RpcException::class.java) { parse(raw) }
        assertEquals(ErrorCodes.INVALID_PARAMS, e.code)
    }

    @Test
    fun tap_singlePoint_defaultsStartTimeToZero() {
        val g = parse("""{"strokes":[{"points":[{"x":540,"y":1860}],"duration":50}]}""")
        assertEquals(1, g.strokes.size)
        val s = g.strokes[0]
        assertEquals(listOf(Point(540, 1860)), s.points)
        assertEquals(0L, s.startTime)
        assertEquals(50L, s.duration)
    }

    @Test
    fun swipe_multiplePoints_parses() {
        val g = parse("""{"strokes":[{"points":[{"x":100,"y":1500},{"x":100,"y":300}],"startTime":0,"duration":300}]}""")
        assertEquals(2, g.strokes[0].points.size)
        assertEquals(Point(100, 300), g.strokes[0].points.last())
    }

    @Test
    fun multitouch_twoParallelStrokes_parses() {
        val g = parse(
            """{"strokes":[
                {"points":[{"x":100,"y":100}],"duration":100},
                {"points":[{"x":900,"y":100}],"duration":100}
            ]}"""
        )
        assertEquals(2, g.strokes.size)
    }

    @Test fun missingStrokes_invalid() = assertInvalid("""{}""")
    @Test fun emptyStrokes_invalid() = assertInvalid("""{"strokes":[]}""")
    @Test fun missingPoints_invalid() = assertInvalid("""{"strokes":[{"duration":50}]}""")
    @Test fun emptyPoints_invalid() = assertInvalid("""{"strokes":[{"points":[],"duration":50}]}""")
    @Test fun missingDuration_invalid() = assertInvalid("""{"strokes":[{"points":[{"x":1,"y":2}]}]}""")
    @Test fun zeroDuration_invalid() = assertInvalid("""{"strokes":[{"points":[{"x":1,"y":2}],"duration":0}]}""")
    @Test fun negativeStartTime_invalid() =
        assertInvalid("""{"strokes":[{"points":[{"x":1,"y":2}],"startTime":-1,"duration":50}]}""")

    @Test
    fun durationOverSystemLimit_invalid() =
        assertInvalid("""{"strokes":[{"points":[{"x":1,"y":2}],"duration":99999}]}""")

    @Test
    fun tooManyStrokes_invalid() {
        val strokes = (1..11).joinToString(",") { """{"points":[{"x":1,"y":2}],"duration":10}""" }
        assertInvalid("""{"strokes":[$strokes]}""")
    }
}
