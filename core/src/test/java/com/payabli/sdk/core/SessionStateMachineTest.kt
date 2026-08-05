package com.payabli.sdk.core

import com.payabli.sdk.core.logging.LogCategory
import com.payabli.sdk.core.logging.RecordingLogSink
import com.payabli.sdk.core.logging.impl.DefaultSdkLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

private val TEST_TIMEOUT = 5.seconds

/**
 * The state machine on its own, including the rules `PayabliSessionTest` cannot reach through the session.
 *
 * A sink per test rather than the process-wide one `PayabliSession` owns, so nothing here can decide the
 * outcome of a later class and this file needs no teardown to say so.
 *
 * The injected logger is not decoration: the default reaches `android.util.Log` and throws "not mocked" on
 * the JVM the moment a cutoff is low enough to emit.
 */
class SessionStateMachineTest {
    private val sink = MutableStateFlow<SdkState>(SdkState.Uninitialized)
    private val subject = machine()

    private fun machine() = SessionStateMachine(sink, DefaultSdkLogger(LogCategory.CORE, RecordingLogSink()))

    @Test
    fun `a machine publishes nothing until it is told to`() {
        assertEquals(SdkState.Uninitialized, sink.value)
    }

    @Test
    fun `marking ready publishes ready`() {
        subject.markReady()

        assertEquals(SdkState.Ready, sink.value)
    }

    @Test
    fun `finishing twice is one transition, not an error`() {
        subject.markReady()
        subject.markReinitializeRequired()
        subject.markReinitializeRequired()

        // Several in-flight requests can each discover the same dead session. The second to notice is
        // reporting the same fact, and making that a failure would push the de-duplication onto every caller.
        assertEquals(SdkState.ReinitializeRequired, sink.value)
    }

    @Test
    fun `a finished machine refuses to become ready again`() {
        subject.markReady()
        subject.markReinitializeRequired()

        subject.markReady()

        // The rule that makes ReinitializeRequired mean something. Re-initializing builds a new session with
        // a new machine, so a machine reviving in place would present a session the host has been told to
        // replace as usable again.
        assertEquals(SdkState.ReinitializeRequired, sink.value)
    }

    @Test
    fun `a machine retired without publishing still refuses to become ready`() {
        subject.markReady()

        // How a session is dropped without reaching the terminal state: nothing is published, because
        // whatever replaces it publishes its own value. Here that is a reset putting the state back.
        subject.finish()
        sink.value = SdkState.Uninitialized

        subject.markReady()

        // Asserted against a value the machine would have to overwrite to fail. Left at Ready, this passes
        // for an implementation that ignores the flag and merely de-duplicates the target it was handed.
        assertEquals(SdkState.Uninitialized, sink.value)
        assertTrue("a retired machine must be finished, not merely quiet", subject.isFinished)
    }

    @Test
    fun `a successor publishes over the terminal value its predecessor left`() {
        subject.markReady()
        subject.markReinitializeRequired()

        val successor = machine()
        successor.markReady()

        // The whole reason the refusal is per machine rather than per published value. Keyed on the value, a
        // replacement could never announce itself and the state would stay terminal for the process's life.
        assertEquals(SdkState.Ready, sink.value)
    }

    @Test
    fun `a finished predecessor cannot publish over its successor`() {
        subject.markReady()
        subject.markReinitializeRequired()
        machine().markReady()

        // A request that decided the session was finished can suspend before it says so, and resume after the
        // host has already re-initialized. It then calls the listener it was built with, which belongs here.
        subject.markReinitializeRequired()

        assertEquals(SdkState.Ready, sink.value)
    }

    @Test
    fun `no observer of the terminal value can see the machine as still usable`() =
        runTest(timeout = TEST_TIMEOUT) {
            var usableWhilePublishing: Boolean? = null

            // Unconfined so the collector resumes on the publishing thread, inside the write itself, which is
            // the earliest any observer can possibly run. A dispatched collector would be scheduled instead
            // and would read the flag long after, which passes whatever the order is.
            val collector =
                backgroundScope.launch(Dispatchers.Unconfined) {
                    sink.collect {
                        if (it == SdkState.ReinitializeRequired) usableWhilePublishing = !subject.isFinished
                    }
                }

            subject.markReady()
            subject.markReinitializeRequired()
            collector.cancel()

            // `install` asks exactly this question. Answered wrongly, a host reacting to the terminal value
            // gets its dead session handed back, or a re-initialize refused, right after being told to
            // re-initialize.
            assertEquals(false, usableWhilePublishing)
        }

    @Test
    fun `the state is observable and carries the transition`() =
        runTest(timeout = TEST_TIMEOUT) {
            val seen = mutableListOf<SdkState>()

            // StateFlow replays its current value to a new collector, so the starting state is seen without
            // racing the writer. That property is why state is a StateFlow and events are not.
            seen += sink.value
            subject.markReady()
            seen += sink.value
            subject.markReinitializeRequired()
            seen += sink.value

            assertEquals(
                listOf(SdkState.Uninitialized, SdkState.Ready, SdkState.ReinitializeRequired),
                seen,
            )
        }
}
