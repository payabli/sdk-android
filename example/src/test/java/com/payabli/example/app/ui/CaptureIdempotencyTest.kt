package com.payabli.example.app.ui

import com.payabli.example.app.config.DemoConfiguration
import com.payabli.example.app.diagnostics.DiagnosticsStore
import com.payabli.example.app.payment.DemoForms
import com.payabli.example.app.payment.capturedPaymentOutcome
import com.payabli.example.app.payment.readyStartup
import com.payabli.example.app.payment.refusedOutcome
import com.payabli.example.app.ui.capture.CaptureViewModel
import com.payabli.sdk.payin.model.PayInException
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
    fun `an answered attempt gives the next one a different key`() {
        val model = captureModel()
        val first =
            model.uiState.value.operation
                .keyOrNull()

        model.onCompleted(capturedPaymentOutcome())

        assertTrue(
            "the key outlived the answer it belonged to",
            first !=
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
        // The one outcome where the service may have taken the payment. A new key would send the retry as a
        // second payment instead of a repeat the service can recognize.
        val model = captureModel()
        val interrupted =
            model.uiState.value.operation
                .keyOrNull()

        model.onFailed(PayInSubmissionState.Failed(PayInException.Interrupted(interrupted)))

        assertEquals(
            interrupted,
            model.uiState.value.operation
                .keyOrNull(),
        )
    }
}

/** The key an operation carries, for the attempts above to compare. */
private fun PayabliPayInOperation.keyOrNull(): String? =
    when (this) {
        is PayabliPayInOperation.Capture -> options.idempotencyKey
        is PayabliPayInOperation.Authorize -> options.idempotencyKey
        is PayabliPayInOperation.StoreMethod -> null
    }
