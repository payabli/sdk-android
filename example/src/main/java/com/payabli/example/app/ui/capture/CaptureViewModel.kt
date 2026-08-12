package com.payabli.example.app.ui.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.payabli.example.app.AppContainer
import com.payabli.example.app.config.DemoConfiguration
import com.payabli.example.app.diagnostics.DiagnosticsStore
import com.payabli.example.app.payment.DemoFormSetup
import com.payabli.example.app.payment.DemoForms
import com.payabli.example.app.payment.PayInStartup
import com.payabli.example.app.payment.PaymentError
import com.payabli.example.app.payment.PaymentResult
import com.payabli.example.app.payment.toPaymentError
import com.payabli.example.app.payment.toPaymentResult
import com.payabli.example.app.ui.payment.PaymentFlowUiState
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

data class CaptureUiState(
    override val setup: DemoFormSetup,
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
     * A capture of the demo amount.
     *
     * Fixed here because the form this screen configures collects no amount: a real integration takes it from
     * the order it is charging for.
     */
    val operation: PayabliPayInOperation =
        PayabliPayInOperation.Capture(
            PayInTransactionOptions(
                paymentDetails = PayInPaymentDetails(totalAmount = BigDecimal("1.10"), serviceFee = BigDecimal("0.10")),
                orderId = "android-example",
            ),
        ),
) : PaymentFlowUiState

/**
 * Scoped to the capture graph, so the result screen reads the same instance the form screen wrote
 * to. A route carries no arbitrary API response.
 */
class CaptureViewModel(
    setup: DemoFormSetup,
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
        _uiState.update { it.copy(isCheckingToken = true, tokenCheckText = "Checking…") }
        viewModelScope.launch {
            val started = startup.start(viewModelScope)
            _uiState.update {
                it.copy(
                    isCheckingToken = false,
                    tokenCheckText = started.text,
                    backendReachable = started.isReady,
                    payments = started.payments,
                )
            }
        }
    }

    fun openSheet() = _uiState.update { it.copy(isSheetOpen = true) }

    fun dismissSheet() = _uiState.update { it.copy(isSheetOpen = false) }

    /**
     * The SDK accepted it.
     *
     * Acknowledged straight away, so the retained outcome does not arrive again after a rotation and push the
     * transaction screen twice for one payment.
     */
    fun onCompleted(outcome: PayInSubmissionState.Succeeded) {
        _uiState.value.payments?.acknowledge()
        onCompleted(outcome.toPaymentResult())
    }

    /** The SDK refused it. The form keeps what the payer typed; this records the reason beside the step. */
    fun onFailed(outcome: PayInSubmissionState.Failed) = onError(outcome.toPaymentError())

    private fun onCompleted(result: PaymentResult) {
        val transaction = result.transaction
        val text =
            if (transaction == null) {
                // The same response the payment-method screen calls a failure. A success glyph and a
                // code here would report a captured payment on a response carrying no transaction.
                "✗ The response carried no transaction."
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
        record("RESPONSE ${result.code} paymentTransaction\nreason=${result.reason}")
        _uiState.update {
            it.copy(
                resultText = text,
                lastResult = result,
                // A response can arrive carrying no transaction, which the text above calls a
                // failure. The flag has to agree with it.
                submitFailed = transaction == null,
                isSheetOpen = false,
                outcomeReady = transaction != null,
            )
        }
    }

    private fun onError(error: PaymentError) {
        record("ERROR paymentTransaction\n${error.displayMessage}")
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
                isSheetOpen = false,
            )
        }
    }

    /** Records only when diagnostics are on, so the setting governs what is kept and not only what is shown. */
    private fun record(line: String) {
        if (diagnosticsEnabled) diagnostics.record(line)
    }

    /** Cleared once navigation has happened, so returning to this screen does not push again. */
    fun outcomeShown() = _uiState.update { it.copy(outcomeReady = false) }

    companion object {
        fun from(container: AppContainer): CaptureViewModel =
            CaptureViewModel(
                setup = DemoForms.capture(),
                startup = container.payInStartup,
                diagnostics = container.diagnostics.capture,
                diagnosticsEnabled = container.configuration.diagnosticsEnabled,
                configuration = container.configuration,
            )
    }
}
