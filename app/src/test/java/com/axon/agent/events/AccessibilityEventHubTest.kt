package com.axon.agent.events

import com.axon.agent.core.ScreenCounter
import com.axon.agent.rpc.RpcMessages
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityEventHubTest {

    private fun field(json: String, key: String): String? =
        RpcMessages.json.parseToJsonElement(json).jsonObject[key]?.jsonPrimitive?.contentOrNull

    private fun intField(json: String, key: String): Int =
        RpcMessages.json.parseToJsonElement(json).jsonObject[key]!!.jsonPrimitive.int

    @Test
    fun change_emitsOneScreenChanged() = runTest {
        val emitted = mutableListOf<String>()
        val screen = ScreenCounter()
        val hub = AccessibilityEventHub(this, StandardTestDispatcher(testScheduler), screen, 80L) { emitted.add(it) }

        hub.onScreenEvent("com.a")
        advanceUntilIdle()

        assertEquals(1, emitted.size)
        assertEquals(1, screen.value)
        assertEquals("screenChanged", field(emitted[0], "event"))
        assertEquals("com.a", field(emitted[0], "package"))
        assertEquals(1, intField(emitted[0], "screen"))
    }

    @Test
    fun burst_coalescesToOne() = runTest {
        val emitted = mutableListOf<String>()
        val screen = ScreenCounter()
        val hub = AccessibilityEventHub(this, StandardTestDispatcher(testScheduler), screen, 80L) { emitted.add(it) }

        hub.onScreenEvent("com.a")
        hub.onScreenEvent("com.a")
        hub.onScreenEvent("com.a")
        advanceUntilIdle()

        assertEquals("burst should collapse to one event", 1, emitted.size)
        assertEquals(1, screen.value)
    }

    @Test
    fun contentChangeOnSameScreen_alsoEmits() = runTest {
        // In-place content change (e.g. a validation error appearing) must produce
        // a signal so event-driven waits work — it is NOT suppressed.
        val emitted = mutableListOf<String>()
        val screen = ScreenCounter()
        val hub = AccessibilityEventHub(this, StandardTestDispatcher(testScheduler), screen, 80L) { emitted.add(it) }

        hub.onScreenEvent("com.a")
        advanceUntilIdle()
        hub.onScreenEvent("com.a") // same package, separate settled change
        advanceUntilIdle()

        assertEquals(2, emitted.size)
        assertEquals(2, screen.value)
    }

    @Test
    fun toast_emitsImmediatelyWithoutDebounce() = runTest {
        val emitted = mutableListOf<String>()
        val hub = AccessibilityEventHub(this, StandardTestDispatcher(testScheduler), ScreenCounter(), 80L) { emitted.add(it) }

        hub.onToast("Wrong password", "com.a")

        assertEquals(1, emitted.size)
        assertEquals("toast", field(emitted[0], "event"))
        assertEquals("Wrong password", field(emitted[0], "text"))
    }

    @Test
    fun blankToast_isIgnored() = runTest {
        val emitted = mutableListOf<String>()
        val hub = AccessibilityEventHub(this, StandardTestDispatcher(testScheduler), ScreenCounter(), 80L) { emitted.add(it) }

        hub.onToast("   ", "com.a")
        hub.onToast(null, "com.a")

        assertTrue(emitted.isEmpty())
    }
}
