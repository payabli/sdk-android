package com.payabli.example.app.demo.terminal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalFailureTest {
    private val notPending =
        Result.failure<String>(
            TerminalFailure("a line", Exception("x"), "3028", asksForSetup = true),
        )
    private val refusedCode = Result.failure<String>(TerminalFailure("a line", Exception("x"), "3024"))

    @Test
    fun `an activation refused as not pending asks for setup again`() {
        assertTrue(setupAskedAfter(previous = false, TerminalAction.Activate, notPending))
    }

    @Test
    fun `any other activation outcome does not`() {
        assertFalse(setupAskedAfter(previous = true, TerminalAction.Activate, refusedCode))
        assertFalse(setupAskedAfter(previous = true, TerminalAction.Activate, Result.success("activated")))
    }

    @Test
    fun `a finished setup clears it, whatever its outcome`() {
        assertFalse(setupAskedAfter(previous = true, TerminalAction.Initialize, Result.success("reader ready")))
        assertFalse(setupAskedAfter(previous = true, TerminalAction.Reinitialize, refusedCode))
    }

    @Test
    fun `a charge leaves it as it was`() {
        assertTrue(setupAskedAfter(previous = true, TerminalAction.Charge, refusedCode))
    }
}
