package com.payabli.sdk.core

import com.payabli.sdk.core.logging.LogCategory
import com.payabli.sdk.core.logging.RecordingLogSink
import com.payabli.sdk.core.logging.impl.DefaultSdkLogger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

private val TEST_TIMEOUT = 5.seconds

/**
 * The state machine on its own, including the one rule `PayabliSessionTest` cannot reach through the session.
 *
 * The injected logger is not decoration: the default reaches `android.util.Log` and throws "not mocked" on
 * the JVM the moment a cutoff is low enough to emit.
 */
class SessionStateMachineTest {
    private val subject = SessionStateMachine(DefaultSdkLogger(LogCategory.CORE, RecordingLogSink()))

    @Test
    fun `a machine starts uninitialized`() {
        assertEquals(SdkState.Uninitialized, subject.state.value)
    }

    @Test
    fun `marking ready publishes ready`() {
        subject.markReady()

        assertEquals(SdkState.Ready, subject.state.value)
    }

    @Test
    fun `finishing twice is one transition, not an error`() {
        subject.markReady()
        subject.markReinitializeRequired()
        subject.markReinitializeRequired()

        // Several in-flight requests can each discover the same dead session. The second to notice is
        // reporting the same fact, and making that a failure would push the de-duplication onto every caller.
        assertEquals(SdkState.ReinitializeRequired, subject.state.value)
    }

    @Test
    fun `a finished machine refuses to become ready again`() {
        subject.markReady()
        subject.markReinitializeRequired()

        subject.markReady()

        // The rule that makes ReinitializeRequired mean something. Re-initializing builds a new session with
        // a new machine, so a machine reviving in place would present a session the host has been told to
        // replace as usable again.
        assertEquals(SdkState.ReinitializeRequired, subject.state.value)
    }

    @Test
    fun `the state is observable and carries the transition`() =
        runTest(timeout = TEST_TIMEOUT) {
            val seen = mutableListOf<SdkState>()

            // StateFlow replays its current value to a new collector, so the starting state is seen without
            // racing the writer. That property is why state is a StateFlow and events are not.
            seen += subject.state.value
            subject.markReady()
            seen += subject.state.value
            subject.markReinitializeRequired()
            seen += subject.state.value

            assertEquals(
                listOf(SdkState.Uninitialized, SdkState.Ready, SdkState.ReinitializeRequired),
                seen,
            )
        }
}
