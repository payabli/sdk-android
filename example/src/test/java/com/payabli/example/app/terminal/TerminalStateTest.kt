package com.payabli.example.app.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalStateTest {
    // --- the chip ---

    @Test
    fun `every state has a label and a tone`() {
        TerminalSessionState.entries.forEach { state ->
            val spec = chipSpecFor(state)
            assertTrue("$state has no label", spec.label.isNotBlank())
        }
    }

    @Test
    fun `ready is the only state that reads as ready`() {
        TerminalSessionState.entries.forEach { state ->
            val expected = if (state == TerminalSessionState.Ready) ChipTone.Ready else null
            if (expected != null) {
                assertEquals(expected, chipSpecFor(state).tone)
            } else {
                assertNotEquals("$state should not read as ready", ChipTone.Ready, chipSpecFor(state).tone)
            }
        }
    }

    @Test
    fun `only expiry and error raise an alert`() {
        val alerting =
            TerminalSessionState.entries.filter { chipSpecFor(it).tone == ChipTone.Alert }
        assertEquals(
            listOf(TerminalSessionState.SessionExpired, TerminalSessionState.Error),
            alerting,
        )
    }

    @Test
    fun `pending activation is not an alert, because nothing is broken`() {
        assertEquals(ChipTone.Pending, chipSpecFor(TerminalSessionState.PendingActivation).tone)
    }

    @Test
    fun `no two states share a label`() {
        val labels = TerminalSessionState.entries.map { chipSpecFor(it).label }
        assertEquals(labels.size, labels.toSet().size)
    }

    // --- the event buffer ---

    @Test
    fun `the buffer starts empty`() {
        assertTrue(EventBuffer().isEmpty)
    }

    @Test
    fun `entries are newest first`() {
        val buffer =
            EventBuffer()
                .add(TerminalEvent(TerminalEventCode.AttestationStarted))
                .add(TerminalEvent(TerminalEventCode.ReaderReady))
        assertEquals(TerminalEventCode.ReaderReady, buffer.entries.first().code)
        assertEquals(TerminalEventCode.AttestationStarted, buffer.entries.last().code)
    }

    @Test
    fun `the buffer holds exactly the limit without dropping`() {
        var buffer = EventBuffer(limit = 3)
        repeat(3) { buffer = buffer.add(TerminalEvent(TerminalEventCode.NfcStarted, "n=$it")) }
        assertEquals(3, buffer.entries.size)
        assertEquals("n=0", buffer.entries.last().detail)
    }

    @Test
    fun `past the limit the oldest entry is the one dropped`() {
        var buffer = EventBuffer(limit = 3)
        repeat(5) { buffer = buffer.add(TerminalEvent(TerminalEventCode.NfcStarted, "n=$it")) }
        assertEquals(3, buffer.entries.size)
        assertEquals("n=4", buffer.entries.first().detail)
        assertEquals("n=2", buffer.entries.last().detail)
    }

    @Test
    fun `clearing empties the buffer`() {
        val buffer = EventBuffer().add(TerminalEvent(TerminalEventCode.ReaderReady)).cleared()
        assertTrue(buffer.isEmpty)
    }

    // --- wire names ---

    @Test
    fun `event codes render as camelCase wire names`() {
        assertEquals("attestationStarted", TerminalEventCode.AttestationStarted.wireName)
        assertEquals("nfcFailed", TerminalEventCode.NfcFailed.wireName)
        assertEquals("devicePendingActivation", TerminalEventCode.DevicePendingActivation.wireName)
    }

    // --- outcome wording ---

    @Test
    fun `a success without detail still names the action`() {
        assertEquals("✓ Enable terminal succeeded", TerminalActionOutcome.success(TerminalAction.Initialize))
    }

    @Test
    fun `a success with detail appends it`() {
        assertEquals(
            "✓ Charge: demo-txn-0001",
            TerminalActionOutcome.success(TerminalAction.Charge, "demo-txn-0001"),
        )
    }

    @Test
    fun `a failure carries the error's own message`() {
        assertEquals(
            "✗ Charge failed: Enter an amount greater than zero",
            TerminalActionOutcome.failure(
                TerminalAction.Charge,
                IllegalArgumentException("Enter an amount greater than zero"),
            ),
        )
    }

    @Test
    fun `a failure with no message falls back to the exception type`() {
        assertEquals(
            "✗ Activate device failed: IllegalStateException",
            TerminalActionOutcome.failure(TerminalAction.Activate, IllegalStateException()),
        )
    }

    @Test
    fun `only a stopped session has a reason to report`() {
        val withReason = TerminalSessionState.entries.filter { sessionFailureReason(it).isNotEmpty() }
        assertEquals(
            listOf(TerminalSessionState.SessionExpired, TerminalSessionState.Error).sorted(),
            withReason.sorted(),
        )
    }

    @Test
    fun `every state the sequence marks failed can say why`() {
        // The step derivation and this reason read the same states. A state added to one and not the
        // other leaves a failed step with a blank reason and its retry unexplained.
        TerminalSessionState.entries
            .filter { chipSpecFor(it).tone == ChipTone.Alert }
            .forEach { assertTrue("$it has no reason", sessionFailureReason(it).isNotEmpty()) }
    }

    @Test
    fun `from picks the right half of the mapping`() {
        assertEquals(
            "✓ Re-initialize succeeded",
            TerminalActionOutcome.from(TerminalAction.Reinitialize, Result.success("")),
        )
        assertEquals(
            "✗ Re-initialize failed: nope",
            TerminalActionOutcome.from(TerminalAction.Reinitialize, Result.failure(IllegalStateException("nope"))),
        )
    }
}
