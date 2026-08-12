package com.payabli.sdk.payin.payment

import com.payabli.sdk.payin.form.PayInField
import com.payabli.sdk.payin.form.PayInFieldError
import com.payabli.sdk.payin.model.PayInException
import com.payabli.sdk.payin.model.PayInFailure
import com.payabli.sdk.payin.model.PayInResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

/**
 * The terminal states carry identity, and the two waiting states carry value.
 *
 * A `StateFlow` conflates by `Any.equals`, so two identical consecutive refusals are one emission from a data
 * class: a payer declined twice for the same reason on the same field sees the form do nothing. `Idle` and
 * `Submitting` are `data object`s, which a caller compares by value.
 */
class PayInSubmissionStateIdentityTest {
    private val timeout = 5.seconds

    private fun declined(): PayInSubmissionState.Failed =
        PayInSubmissionState.Failed(
            cause = PayInException.Refused(PayInFailure("D1001", "Insufficient funds", null, "retry", 200)),
            fieldErrors = mapOf(PayInField.CardNumber to PayInFieldError.NotAccepted),
        )

    @Test
    fun `two refusals describing the same thing are not equal`() {
        assertNotEquals("a data class here would swallow the second decline", declined(), declined())
    }

    @Test
    fun `two successes carrying the same result are not equal either`() {
        val result = PayInResult(code = "A0000", transaction = null)
        assertNotEquals(
            PayInSubmissionState.Succeeded.Payment(result),
            PayInSubmissionState.Succeeded.Payment(result),
        )
    }

    @Test
    fun `the states a caller compares by value are equal`() {
        // `assertEquals(Submitting, state)` reads across the test suite, and this is what makes it work.
        assertEquals(PayInSubmissionState.Idle, PayInSubmissionState.Idle)
        assertEquals(PayInSubmissionState.Submitting, PayInSubmissionState.Submitting)
    }

    @Test
    fun `a second identical refusal is published rather than conflated away`() =
        runTest(timeout = timeout) {
            val sink = MutableStateFlow<PayInSubmissionState>(PayInSubmissionState.Idle)
            val seen = CopyOnWriteArrayList<PayInSubmissionState>()
            // Unconfined, so the collector is attached and each write reaches it before the next line. On the
            // test dispatcher the collector would not start until this coroutine suspends, and a StateFlow
            // replays only its latest value — so every assertion below would pass on one emission.
            val watcher = launch(Dispatchers.Unconfined) { sink.collect { seen += it } }

            sink.value = declined()
            sink.value = PayInSubmissionState.Submitting
            sink.value = declined()
            watcher.cancel()

            assertEquals("a refusal went missing between two identical ones", 4, seen.size)
        }
}
