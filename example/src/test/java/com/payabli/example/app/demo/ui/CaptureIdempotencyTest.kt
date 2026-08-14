package com.payabli.example.app.demo.ui

import com.payabli.example.app.demo.config.DemoConfiguration
import com.payabli.example.app.demo.diagnostics.DiagnosticsStore
import com.payabli.example.app.demo.ui.capture.CaptureViewModel
import com.payabli.example.app.sdk.DemoForms
import com.payabli.example.app.sdk.capturedPaymentOutcome
import com.payabli.example.app.sdk.readyStartup
import com.payabli.example.app.sdk.refusedOutcome
import com.payabli.sdk.core.model.PayabliErrorCode
import com.payabli.sdk.core.model.PayabliException
import com.payabli.sdk.core.model.PayabliValidationException
import com.payabli.sdk.payin.model.PayInException
import com.payabli.sdk.payin.model.PayInFailure
import com.payabli.sdk.payin.payment.PayInSubmissionState
import com.payabli.sdk.payin.payment.PayabliPayInOperation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The key a capture carries, per attempt.
 *
 * A capture repeated without one charges twice, which is why `PayInException.Interrupted` carries the key a
 * retry needs. Repeated with the key of an attempt the service has already answered, a second deliberate
 * payment comes back as the first one's result, so the key cannot simply be fixed for the screen either.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CaptureIdempotencyTest {
    @Before
    fun installMainDispatcher() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun removeMainDispatcher() = Dispatchers.resetMain()

    private fun captureModel() =
        CaptureViewModel(
            setup = DemoForms.capture(),
            startup = readyStartup(),
            diagnostics = DiagnosticsStore(),
            diagnosticsEnabled = true,
            configuration = DemoConfiguration.fromBuildConfig(),
        )

    @Test
    fun `the first attempt carries a key`() {
        assertTrue(
            "a capture went out with no idempotency key",
            !captureModel()
                .uiState.value.operation
                .keyOrNull()
                .isNullOrBlank(),
        )
    }

    @Test
    fun `an approved payment keeps its key until the payer asks for another`() {
        // The form leaves the screen when a payment completes, so nothing can be submitted under this key until
        // `startOver` mints a new one. Rotating here would hand a fresh key to a retry of a payment that was
        // taken and described incompletely.
        val model = captureModel()
        val first =
            model.uiState.value.operation
                .keyOrNull()

        model.onCompleted(capturedPaymentOutcome())

        assertEquals(
            first,
            model.uiState.value.operation
                .keyOrNull(),
        )
    }

    @Test
    fun `a refusal is an answer, so the next attempt is a new payment`() {
        val model = captureModel()
        val first =
            model.uiState.value.operation
                .keyOrNull()

        model.onFailed(refusedOutcome())

        assertTrue(
            "a refused attempt kept its key",
            first !=
                model.uiState.value.operation
                    .keyOrNull(),
        )
    }

    @Test
    fun `an interrupted attempt keeps the key its retry needs`() {
        // A new key would send the retry as a second payment instead of a repeat the service can recognize.
        val model = captureModel()
        val interrupted =
            model.uiState.value.operation
                .keyOrNull()

        model.onFailed(PayInSubmissionState.Failed(PayInException.Interrupted(), retryKey = interrupted))

        assertEquals(
            interrupted,
            model.uiState.value.operation
                .keyOrNull(),
        )
    }

    @Test
    fun `starting over asks for another payment, so it carries another key`() {
        // The payer is asking for a second payment, and the service refuses one that arrives under the first
        // one's key. An interruption keeps the key for a retry; this is not a retry.
        val model = captureModel()
        model.onFailed(PayInSubmissionState.Failed(PayInException.Interrupted(), retryKey = "interrupted-key"))
        val kept =
            model.uiState.value.operation
                .keyOrNull()

        model.startOver()

        assertTrue(
            "the next payment went out under the interrupted attempt's key",
            kept !=
                model.uiState.value.operation
                    .keyOrNull(),
        )
    }

    @Test
    fun `every outcome that leaves the attempt unanswered keeps its key`() {
        // A cancellation is not the only one. The request may have reached the service and been taken in each
        // of these: a read that timed out, a 5xx, a 2xx that would not decode. Retried under a fresh key, all
        // of them charge the payer a second time.
        //
        // Each carries the attempt's key, because that is what the SDK puts on `retryKey` for exactly these.
        val unanswered =
            mapOf(
                "a read that timed out" to PayabliNetworkException("timeout"),
                "a service error" to PayInException.ServiceError(serviceFailure()),
                "a 2xx that would not decode" to PayInException.Undecodable(),
            )

        unanswered.forEach { (outcome, cause) ->
            val model = captureModel()
            val key =
                model.uiState.value.operation
                    .keyOrNull()

            model.onFailed(PayInSubmissionState.Failed(cause, retryKey = key))

            assertEquals(
                "$outcome rotated the key, so a retry charges again",
                key,
                model.uiState.value.operation
                    .keyOrNull(),
            )
        }
    }

    @Test
    fun `a submission refused while another is in flight keeps the key that one holds`() {
        // Nothing was sent, so no key is owed a retry, but the attempt still running is carrying this one and
        // rotating would leave its own retry unable to name it.
        val model = captureModel()
        val key =
            model.uiState.value.operation
                .keyOrNull()

        model.onFailed(PayInSubmissionState.Failed(PayInException.AlreadySubmitting()))

        assertEquals(
            "the key the in-flight attempt is holding was rotated out from under it",
            key,
            model.uiState.value.operation
                .keyOrNull(),
        )
    }

    @Test
    fun `a rejected field is an answer, so the correction goes out as its own request`() {
        // The service saw this one and rejected it, and nothing was charged. Correcting the field is the whole
        // point of the form staying up, and what the payer sends next is a different request: under the old key
        // it asks the service to treat a changed body as a repeat of the rejected one.
        //
        // A value this module refuses before sending rotates too. Nothing reached the service, so a fresh key
        // costs nothing, and the two arrive here as the same code.
        val answered =
            mapOf(
                "a field the service rejected" to PayabliValidationException(httpStatus = 400),
                "a value refused before sending" to PayInException.InvalidInput(null, "Enter a card number"),
            )

        answered.forEach { (outcome, cause) ->
            val model = captureModel()
            val key =
                model.uiState.value.operation
                    .keyOrNull()

            model.onFailed(PayInSubmissionState.Failed(cause))

            assertTrue(
                "$outcome kept its key, so the next attempt repeats a request the service already answered",
                key !=
                    model.uiState.value.operation
                        .keyOrNull(),
            )
        }
    }
}

/** A transport failure, which arrives as the core type rather than one of `PayInException`'s. */
private class PayabliNetworkException(
    detail: String,
) : PayabliException(PayabliErrorCode.NETWORK_ERROR, "The request did not complete", detail)

private fun serviceFailure() =
    PayInFailure(code = "E0002", reason = "Service error", explanation = null, action = null, httpStatus = 500)

/** The key an operation carries, for the attempts above to compare. */
private fun PayabliPayInOperation.keyOrNull(): String? =
    when (this) {
        is PayabliPayInOperation.Capture -> options.idempotencyKey
        is PayabliPayInOperation.Authorize -> options.idempotencyKey
        is PayabliPayInOperation.StoreMethod -> null
    }
