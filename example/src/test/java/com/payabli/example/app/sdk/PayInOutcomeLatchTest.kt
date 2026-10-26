package com.payabli.example.app.sdk

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The window a token recheck must not enter, at the latch that holds it.
 *
 * Pinned here rather than on the handle: its `PayabliPayIn` is sealed, so a JVM test can neither build
 * one nor stand in for one.
 */
class PayInOutcomeLatchTest {
    private val flight = MutableStateFlow(false)

    /** Unconfined: each value change runs the watcher inline, so assertions read a settled window. */
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @Test
    fun `a submission opens the window, and it stays open past the submission's end`() {
        val latch = PayInOutcomeLatch(flight, scope)

        flight.value = true
        assertTrue("the submission did not open the window", latch.holds)

        flight.value = false
        assertTrue("the window closed with the flight, leaving the outcome standing", latch.holds)

        latch.taken()
        assertFalse("delivery left the window open", latch.holds)
    }

    @Test
    fun `nothing opens a window before a submission runs`() {
        val latch = PayInOutcomeLatch(flight, scope)

        assertFalse("a window opened with nothing running", latch.holds)
    }

    @Test
    fun `a submission after a delivery opens the window again`() {
        val latch = PayInOutcomeLatch(flight, scope)

        flight.value = true
        flight.value = false
        latch.taken()
        assertFalse("delivery left the window open", latch.holds)

        flight.value = true
        assertTrue("the submission after a delivery did not open the window", latch.holds)
    }

    @Test
    fun `a direct call's submission opens nothing, while it runs or after it ends`() =
        runTest {
            val latch = PayInOutcomeLatch(flight, scope)

            latch.withoutLatching {
                flight.value = true
                assertFalse("the direct call's own flight opened the window", latch.holds)
                flight.value = false
                assertFalse("the direct call's ended flight left the window open", latch.holds)
            }
        }

    @Test
    fun `a direct call leaves the watcher free for a submission that follows it`() =
        runTest {
            val latch = PayInOutcomeLatch(flight, scope)

            latch.withoutLatching { flight.value = false }

            flight.value = true
            assertTrue("the direct call left the window latched shut", latch.holds)
        }
}
