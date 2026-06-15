package com.axon.agent.core

import java.util.concurrent.atomic.AtomicInteger

/**
 * Monotonic "screen state" counter, shared across dumps, screenshots and events.
 *
 * It is one of the few permitted drops of state. It is bumped (Stage 7) only on a
 * genuine screen change; every dump/screenshot reports the current [value] so the
 * PC can correlate a response with the screen generation it describes.
 */
class ScreenCounter {
    private val counter = AtomicInteger(0)

    val value: Int get() = counter.get()

    /** Advance to the next generation; returns the new value. */
    fun next(): Int = counter.incrementAndGet()
}
