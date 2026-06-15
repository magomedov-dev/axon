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
    fun stateChange_emitsOneScreenChanged() = runTest {
        val emitted = mutableListOf<String>()
        val screen = ScreenCounter()
        val hub = AccessibilityEventHub(this, StandardTestDispatcher(testScheduler), screen, 80L) { emitted.add(it) }

        hub.onScreenEvent(stateChange = true, packageName = "com.a", windowId = 1)
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

        hub.onScreenEvent(true, "com.a", 1)
        hub.onScreenEvent(false, "com.a", 1)
        hub.onScreenEvent(false, "com.a", 1)
        advanceUntilIdle()

        assertEquals("burst should collapse to one event", 1, emitted.size)
        assertEquals(1, screen.value)
    }

    @Test
    fun contentOnlyOnSameScreen_isSuppressed() = runTest {
        val emitted = mutableListOf<String>()
        val screen = ScreenCounter()
        val hub = AccessibilityEventHub(this, StandardTestDispatcher(testScheduler), screen, 80L) { emitted.add(it) }

        hub.onScreenEvent(true, "com.a", 1)
        advanceUntilIdle()
        // content churn, same package/window, no state change -> no new event
        hub.onScreenEvent(false, "com.a", 1)
        advanceUntilIdle()

        assertEquals(1, emitted.size)
        assertEquals(1, screen.value)
    }

    @Test
    fun packageOrWindowChange_emitsAgain() = runTest {
        val emitted = mutableListOf<String>()
        val screen = ScreenCounter()
        val hub = AccessibilityEventHub(this, StandardTestDispatcher(testScheduler), screen, 80L) { emitted.add(it) }

        hub.onScreenEvent(true, "com.a", 1)
        advanceUntilIdle()
        hub.onScreenEvent(false, "com.b", 2)
        advanceUntilIdle()

        assertEquals(2, emitted.size)
        assertEquals(2, screen.value)
        assertEquals("com.b", field(emitted[1], "package"))
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
