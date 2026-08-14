package com.payabli.example.app.demo.ui.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.payabli.example.app.AppContainer
import com.payabli.example.app.demo.config.DemoConfiguration
import com.payabli.example.app.demo.diagnostics.DiagnosticsStore
import com.payabli.example.app.demo.net.checkToken
import com.payabli.example.app.demo.payment.PaymentError
import com.payabli.example.app.demo.payment.PaymentResult
import com.payabli.example.app.demo.ui.payment.PaymentFlowUiState
import com.payabli.example.app.sdk.PayInFormSetup
import com.payabli.example.app.sdk.PayInForms
import com.payabli.example.app.sdk.PayInStartup
import com.payabli.example.app.sdk.isBusy
import com.payabli.example.app.sdk.payInStartup
import com.payabli.example.app.sdk.toPaymentError
import com.payabli.example.app.sdk.toPaymentResult
import com.payabli.sdk.payin.model.PayInException
import com.payabli.sdk.payin.model.PayInPaymentDetails
import com.payabli.sdk.payin.model.PayInTransactionOptions
import com.payabli.sdk.payin.payment.PayInSubmissionState
import com.payabli.sdk.payin.payment.PayabliPayInOperation
import com.payabli.sdk.payin.payment.PayabliPayInPaymentFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

data class CaptureUiState(
    override val setup: PayInFormSetup,
    override val resultText: String = "",
    /** Raised only when the completion carried the payload this screen exists to show. */
    val outcomeReady: Boolean = false,
    val lastResult: PaymentResult? = null,
    override val diagnostics: List<String> = emptyList(),
    override val diagnosticsEnabled: Boolean = true,
    override val prefillEnabled: Boolean = false,
    override val isSheetOpen: Boolean = false,
    /** What this screen is pointed at, shown in one line. The full set is on Setup. */
    override val entryPoint: String = "",
    override val host: String = "",
    /** The last submission failed, so its step keeps the reason and the retry. */
    val submitFailed: Boolean = false,
    /** What the last token check said, empty until one has run. */
    override val tokenCheckText: String = "",
    /** The token endpoint answered. The form stays blocked until it has. */
    val backendReachable: Boolean = false,
    override val isCheckingToken: Boolean = false,
    /** What this screen submits through, once the session behind it exists. */
    val payments: PayabliPayInPaymentFlow? = null,
    /**
     * A capture of the demo amount, carrying the key that makes a repeat safe.
     *
     * The amount is fixed here because the form this screen configures collects no amount: a real integration
     * takes it from the order it is charging for.
     */
    val operation: PayabliPayInOperation = captureOf(UUID.randomUUID().toString()),
) : PaymentFlowUiState {
    override val finished: Boolean get() = lastResult != null
}

/**
 * A capture of the demo amount under [idempotencyKey].
 *
 * Without a key the service cannot recognize a repeat, so a submission whose outcome is unknown cannot be
 * retried: `PayInSubmissionState.Failed.retryKey` names it precisely so it can be. One key per attempt, kept
 * while that attempt's outcome is unknown and replaced once the service has answered.
 */
private fun captureOf(idempotencyKey: String): PayabliPayInOperation.Capture =
    PayabliPayInOperation.Capture(
        PayInTransactionOptions(
            paymentDetails = PayInPaymentDetails(totalAmount = BigDecimal("1.10"), serviceFee = BigDecimal("0.10")),
            orderId = "android-example",
            idempotencyKey = idempotencyKey,
            // A paypoint can refuse a payment that names no customer it can identify. The sandbox one takes
            // this card with a billing email or a customer number and answers 400 E7020 with neither, so the
            // request does not depend on which of those the payer filled in.
            forceCustomerCreation = true,
        ),
    )

/**
 * Scoped to the capture graph, so the result screen reads the same instance the form screen wrote
 * to. A route carries no arbitrary API response.
 */
class CaptureViewModel(
    setup: PayInFormSetup,
    private val startup: PayInStartup,
    private val diagnostics: DiagnosticsStore,
    private val diagnosticsEnabled: Boolean,
    private val configuration: DemoConfiguration,
) : ViewModel() {
    private val _uiState =
        MutableStateFlow(
            CaptureUiState(
                setup = setup,
                diagnosticsEnabled = diagnosticsEnabled,
                prefillEnabled = configuration.prefillEnabled,
                entryPoint = configuration.entryPoint,
                host = configuration.environment.host,
            ),
        )
    val uiState: StateFlow<CaptureUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            diagnostics.messages.collect { messages ->
                _uiState.update { it.copy(diagnostics = messages) }
            }
        }
    }

    /** The first step of the sequence. [checkToken] says what it reports and why. */
    fun checkToken() {
        if (_uiState.value.isCheckingToken) return
        // A recheck builds a session and replaces the flow this screen submits through. Replaced while that
        // flow holds a request or an outcome, what it holds is lost, so the step waits instead.
        if (_uiState.value.payments.isBusy()) return
        _uiState.update { it.copy(isCheckingToken = true, tokenCheckText = "Checking…") }
        viewModelScope.launch {
            // A throw out of the start would otherwise skip the write below, and the flag it would have
            // cleared is the one the guard above reads: the step that offers the retry never runs again and
            // the screen keeps "Checking…". Reported as the failed check it is, which leaves the retry.
            val started =
                try {
                    startup.start(viewModelScope)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    PayInStartup.Started("✗ The token check could not run: ${failure.message}", false, null)
                }
            _uiState.update {
                it.copy(
                    isCheckingToken = false,
                    tokenCheckText = started.text,
                    backendReachable = started.isReady,
                    // A payment can start, and finish, while the recheck above is suspended. A flow still
                    // holding either is kept: replacing it loses the request or the outcome.
                    payments = it.payments?.takeIf { flow -> flow.isBusy() } ?: started.payments,
                )
            }
        }
    }

    fun openSheet() = _uiState.update { it.copy(isSheetOpen = true) }

    fun dismissSheet() = _uiState.update { it.copy(isSheetOpen = false) }

    /** The SDK accepted it. */
    fun onCompleted(outcome: PayInSubmissionState.Succeeded) {
        onCompleted(outcome.toPaymentResult())
    }

    /**
     * The SDK refused it.
     *
     * What the panel records is the exception's own `toString`, which carries the error code and nothing from
     * the wire. `reason` and `detail` are displayable and never loggable: the service echoes submitted values
     * into some of them, and this panel is on screen and gets copied into bug reports.
     */
    fun onFailed(outcome: PayInSubmissionState.Failed) {
        rotateIdempotencyKey(outcome)
        record("ERROR paymentTransaction\n${outcome.cause}")
        onError(outcome.toPaymentError())
    }

    private fun onCompleted(result: PaymentResult) {
        val transaction = result.transaction
        val text =
            if (transaction == null) {
                // An `A` code is an approval, so this is a payment the service took and described
                // incompletely. Reading it as a failure invites a payer to pay twice.
                "✓ Code: ${result.code}\nThe response carried no transaction to identify it by."
            } else {
                // Marked, and carrying the identifiers a reader would otherwise leave the screen for.
                listOfNotNull(
                    "✓ Code: ${result.code}",
                    result.reason?.let { "Reason: $it" },
                    transaction.paymentTransactionId.let { "Payment transaction: $it" },
                    transaction.gatewayTransactionId?.let { "Gateway transaction: $it" },
                    transaction.method?.let { "Method: $it" },
                    transaction.operation?.let { "Operation: $it" },
                ).joinToString("\n")
            }
        record("RESPONSE paymentTransaction\ncode=${result.code}")
        _uiState.update {
            it.copy(
                resultText = text,
                lastResult = result,
                // Not a failed submission: the payment was taken. The step below stays where it is because the
                // screen it pushes describes a transaction, and this response named none.
                submitFailed = false,
                isSheetOpen = false,
                outcomeReady = transaction != null,
            )
        }
    }

    /**
     * The SDK refused it.
     *
     * The sheet is left as it is. It holds the form holding the values the service refused, and the form
     * beneath it is a different instance with its own: dismissed, the payer has nothing left to correct.
     */
    private fun onError(error: PaymentError) {
        _uiState.update {
            // The outcome signal and its payload are cleared, not left standing. A failure arriving
            // after a completion but before navigation consumed the signal would otherwise push the
            // transaction screen on top of the error just recorded, showing the previous payment as
            // though it were this one.
            it.copy(
                resultText = "✗ ${error.displayMessage}",
                outcomeReady = false,
                lastResult = null,
                submitFailed = true,
            )
        }
    }

    /** Records only when diagnostics are on, so the setting governs what is kept and not only what is shown. */
    private fun record(line: String) {
        if (diagnosticsEnabled) diagnostics.record(line)
    }

    /** Cleared once navigation has happened, so returning to this screen does not push again. */
    fun outcomeShown() = _uiState.update { it.copy(outcomeReady = false) }

    /**
     * Back to the form step, for a second payment.
     *
     * A finished step draws no controls, so the form leaves the screen once a payment completes and this is the
     * only way back to it. The key goes with the result: the payer is asking for another payment, and the
     * service refuses a second one that arrives under the first one's key.
     */
    fun startOver() =
        _uiState.update {
            it.copy(
                resultText = "",
                submitFailed = false,
                lastResult = null,
                outcomeReady = false,
                operation = captureOf(UUID.randomUUID().toString()),
            )
        }

    /**
     * A new key for the next attempt, once this one has an answer.
     *
     * `retryKey` is the SDK's own statement of which failures left the attempt's fate unknown — a read that
     * timed out, a 5xx, a 2xx that would not decode, a cancellation. Those keep the key, because a retry
     * carrying it is recognized as the repeat it is instead of charging the payer twice.
     *
     * Everything else was answered: no payment is outstanding, the form stays on screen, and what the payer
     * sends next is a different request. A decline and a rejected field are both in that group, and a rejected
     * field is the one that matters, because correcting it is the whole point of the form staying up.
     *
     * `AlreadySubmitting` is the exception: nothing was sent, and the submission still in flight is holding
     * the key.
     *
     * An approval does not come through here. The form leaves the screen when a payment completes, so the way
     * to a second one is [startOver], which mints its own key.
     */
    private fun rotateIdempotencyKey(outcome: PayInSubmissionState.Failed) {
        if (outcome.retryKey != null || outcome.cause is PayInException.AlreadySubmitting) return
        _uiState.update { it.copy(operation = captureOf(UUID.randomUUID().toString())) }
    }

    companion object {
        fun from(container: AppContainer): CaptureViewModel =
            CaptureViewModel(
                setup = PayInForms.capture(),
                startup = container.payInStartup,
                diagnostics = container.diagnostics.capture,
                diagnosticsEnabled = container.configuration.diagnosticsEnabled,
                configuration = container.configuration,
            )
    }
}
