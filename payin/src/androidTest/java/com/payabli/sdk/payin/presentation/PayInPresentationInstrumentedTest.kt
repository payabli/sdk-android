package com.payabli.sdk.payin.presentation

import android.app.Activity
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The SDK-owned Activity launches one contract and hands its result back to the call waiting on it. [EchoActivity]
 * stays on top until a test answers it, so each test acts while the contract's screen is up.
 *
 * The device has to be awake and unlocked: a locked screen keeps every Activity stopped.
 */
@RunWith(AndroidJUnit4::class)
class PayInPresentationInstrumentedTest {
    private lateinit var scenario: ActivityScenario<ComponentActivity>
    private val scope = CoroutineScope(Dispatchers.Default)

    @Before
    fun setUp() {
        EchoActivity.reset()
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
    }

    @After
    fun tearDown() {
        // A test that failed with the echo still up leaves it over the host.
        if (EchoActivity.CURRENT.get() != null) EchoActivity.answer(Activity.RESULT_CANCELED)
        scenario.close()
    }

    @Test
    fun returnsTheContractsResult() {
        val outcome = present("approved")
        awaitEcho()

        EchoActivity.answer(Activity.RESULT_OK)

        val result = await(outcome)
        assertTrue("$result", result is PayInPresentationOutcome.Completed)
        assertEquals("approved", (result as PayInPresentationOutcome.Completed).value)
    }

    @Test
    fun aCancelComesBackAsACancel() {
        val outcome = present("approved")
        awaitEcho()

        EchoActivity.answer(Activity.RESULT_CANCELED)

        assertEquals(PayInPresentationOutcome.Cancelled, await(outcome))
    }

    @Test
    fun aConfigurationChangeWhileLaunchedDoesNotRelaunchTheContract() {
        val outcome = present("approved")
        awaitEcho()
        val before = awaitPresentation()

        InstrumentationRegistry.getInstrumentation().runOnMainSync { before.recreate() }
        val after = awaitUntilNotNull { presentationActivities().firstOrNull { it !== before } }
        assertNotSame(before, after)

        EchoActivity.answer(Activity.RESULT_OK)

        val result = await(outcome)
        assertTrue(
            "the result did not reach the recreated Activity: $result",
            result is PayInPresentationOutcome.Completed,
        )
        assertEquals("the contract was launched again after the configuration change", 1, EchoActivity.LAUNCHES.get())
    }

    @Test
    fun aCancelledCallerFinishesTheActivity() {
        val outcome = present("approved")
        awaitEcho()
        awaitPresentation()

        outcome.cancel()

        awaitUntil("the presentation Activity outlived the call that opened it") { presentationActivities().isEmpty() }
        assertEquals(0, PayInPresentation.pendingCount())
    }

    @Test
    fun finishingWithoutAResultIsACancel() {
        val outcome = present("approved")
        awaitEcho()
        val presentation = awaitPresentation()

        InstrumentationRegistry.getInstrumentation().runOnMainSync { presentation.finish() }

        assertEquals(PayInPresentationOutcome.Cancelled, await(outcome))
        assertEquals(0, PayInPresentation.pendingCount())
    }

    /**
     * Starts a presentation of [EchoActivity] from the host Activity, as a presented path would.
     *
     * @param value what the echo returns on `RESULT_OK`.
     * @return the outcome the waiting call receives.
     */
    private fun present(value: String): Deferred<PayInPresentationOutcome<String?>> {
        var host: ComponentActivity? = null
        scenario.onActivity { host = it }
        val activity = requireNotNull(host)
        val input = Intent(activity, EchoActivity::class.java).putExtra(EchoActivity.EXTRA_VALUE, value)
        return scope.async {
            PayInPresentation.present(activity, ActivityResultContracts.StartActivityForResult(), input, ::outcomeOf)
        }
    }

    private fun outcomeOf(result: ActivityResult): PayInPresentationOutcome<String?> =
        if (result.resultCode == Activity.RESULT_OK) {
            PayInPresentationOutcome.Completed(result.data?.getStringExtra(EchoActivity.EXTRA_VALUE))
        } else {
            PayInPresentationOutcome.Cancelled
        }

    private fun <T> await(outcome: Deferred<T>): T = runBlocking { withTimeout(TIMEOUT_MS) { outcome.await() } }

    private fun awaitEcho() = awaitUntil("the contract's screen never opened") { EchoActivity.CURRENT.get() != null }

    private fun awaitPresentation(): PayInPresentationActivity =
        awaitUntilNotNull {
            presentationActivities().firstOrNull()
        }

    /** Every presentation Activity not yet destroyed, read on the main thread as the monitor requires. */
    private fun presentationActivities(): List<PayInPresentationActivity> {
        val found = mutableListOf<PayInPresentationActivity>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            Stage.entries
                .filter { it != Stage.DESTROYED }
                .flatMap { monitor.getActivitiesInStage(it) }
                .filterIsInstance<PayInPresentationActivity>()
                .forEach { if (it !in found) found += it }
        }
        return found
    }

    private fun awaitUntil(
        message: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { message }
            Thread.sleep(POLL_MS)
        }
    }

    private fun <T : Any> awaitUntilNotNull(read: () -> T?): T {
        var value: T? = null
        awaitUntil("timed out waiting for the presentation Activity") { read().also { value = it } != null }
        return requireNotNull(value)
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val POLL_MS = 50L
    }
}
