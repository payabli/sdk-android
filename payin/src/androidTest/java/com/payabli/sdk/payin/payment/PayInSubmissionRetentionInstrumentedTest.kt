package com.payabli.sdk.payin.payment

import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.payabli.sdk.core.network.PayabliTransport
import com.payabli.sdk.payin.client.RecordingLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * What survives what, for a submission in flight.
 *
 * Needs a real Activity: a configuration change and a lifecycle move are the two things being asserted, and
 * neither exists on the JVM. One question per test, so a failure names which retention guarantee went.
 *
 * **The flow is held in a `ViewModel`, which is what the KDoc tells a host to do.** That is the whole
 * mechanism under test: `viewModelScope` outlives a configuration change, so the request that was already at
 * the service still has somewhere to deliver its outcome.
 *
 * Process death is not here and is not expected to survive. It is the manual tier, in
 * `payin/src/androidTest/PROCESS-DEATH.md`.
 *
 * **The device has to be awake and unlocked.** A locked screen keeps the Activity at `STOPPED`, and every test
 * here then fails with `Activity never becomes requested state "[RESUMED]"`, which names the lifecycle and not
 * the lock. `adb shell input keyevent KEYCODE_WAKEUP && adb shell wm dismiss-keyguard` first.
 */
@RunWith(AndroidJUnit4::class)
class PayInSubmissionRetentionInstrumentedTest {
    /** A host, in the shape the flow's KDoc asks for: one flow, scoped to a ViewModel. */
    class FlowHost(
        transport: PayabliTransport,
    ) : ViewModel() {
        val payments: PayabliPayInPaymentFlow =
            PayabliPayInPaymentFlow(
                transport = transport,
                entryPoint = TEST_ENTRY_POINT,
                scope = viewModelScope,
                dispatcher = Dispatchers.IO,
                logger = RecordingLogger(),
            )
    }

    private lateinit var transport: GatedPayInTransport

    /** Every state the flow published, so "the outcome arrived once" is a count. */
    private val published = CopyOnWriteArrayList<PayInSubmissionState>()
    private var watcher: Job? = null

    private val factory =
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = FlowHost(transport) as T
        }

    @Before
    fun setUp() {
        transport = GatedPayInTransport.answering(APPROVED_TRANSACTION)
    }

    @After
    fun tearDown() {
        // Released first: a test that failed mid-flight leaves a coroutine parked on the gate.
        transport.release()
        watcher?.cancel()
    }

    @Test
    fun aSubmissionInFlightSurvivesARotation() {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            var before: FlowHost? = null
            scenario.onActivity { activity ->
                before =
                    hostFor(activity).also {
                        watch(it)
                        it.payments.start(captureOf(), cardForm())
                    }
            }
            awaitArrival()

            scenario.recreate()

            var after: FlowHost? = null
            scenario.onActivity { activity -> after = hostFor(activity) }
            assertSame("the host was rebuilt, so the submission it was running is gone", before, after)
            assertEquals(PayInSubmissionState.Submitting, after?.payments?.state?.value)

            transport.release()
            val terminal = awaitTerminal(requireNotNull(after))
            assertTrue("$terminal", terminal is PayInSubmissionState.Succeeded)
            assertEquals("the request went to the wire more than once", 1, transport.sent.size)
            assertEquals(
                "the outcome was published more than once",
                1,
                published.count { it is PayInSubmissionState.Succeeded },
            )
        }
    }

    @Test
    fun aSubmissionInFlightSurvivesBackgroundAndReturn() {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            var host: FlowHost? = null
            scenario.onActivity { activity ->
                host = hostFor(activity).also { it.payments.start(captureOf(), cardForm()) }
            }
            awaitArrival()

            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)

            assertEquals(PayInSubmissionState.Submitting, host?.payments?.state?.value)
            assertEquals("a second submission was started on the way back", 1, transport.sent.size)

            transport.release()
            val terminal = awaitTerminal(requireNotNull(host))
            assertTrue("$terminal", terminal is PayInSubmissionState.Succeeded)
        }
    }

    private fun hostFor(owner: ViewModelStoreOwner): FlowHost = ViewModelProvider(owner, factory)[FlowHost::class.java]

    private fun watch(host: FlowHost) {
        watcher = CoroutineScope(Dispatchers.Unconfined).launch { host.payments.state.collect { published += it } }
    }

    /** Waits until a request is at the transport, so nothing below has to guess when that happened. */
    private fun awaitArrival() = runBlocking { withTimeout(TIMEOUT_MILLIS) { transport.arrived.await() } }

    private fun awaitTerminal(host: FlowHost): PayInSubmissionState =
        runBlocking {
            withTimeout(TIMEOUT_MILLIS) {
                host.payments.state.first {
                    it is PayInSubmissionState.Succeeded || it is PayInSubmissionState.Failed
                }
            }
        }

    private companion object {
        /** A device under load needs this long, and a wedge has to fail the test instead of hanging the run. */
        const val TIMEOUT_MILLIS = 5_000L
    }
}
