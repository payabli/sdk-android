package com.payabli.example.app.ui.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.payabli.example.app.AppContainer
import com.payabli.example.app.diagnostics.DiagnosticsStore
import com.payabli.example.app.payment.DemoFormSetup
import com.payabli.example.app.payment.PaymentError
import com.payabli.example.app.payment.PaymentFailure
import com.payabli.example.app.payment.PaymentFlowController
import com.payabli.example.app.payment.PaymentResult
import com.payabli.sdk.payin.form.PayInFormValues
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CaptureUiState(
    val setup: DemoFormSetup,
    val resultText: String = "",
    /** Raised only when the completion carried the payload this screen exists to show. */
    val outcomeReady: Boolean = false,
    val lastResult: PaymentResult? = null,
    val diagnostics: List<String> = emptyList(),
    val diagnosticsEnabled: Boolean = true,
    val isSheetOpen: Boolean = false,
    val isSubmitting: Boolean = false,
)

/**
 * Scoped to the capture graph, so the result screen reads the same instance the form screen wrote
 * to. Passing the result as a navigation argument would mean encoding an arbitrary API response into
 * a route.
 */
class CaptureViewModel(
    private val flow: PaymentFlowController,
    private val diagnostics: DiagnosticsStore,
    private val diagnosticsEnabled: Boolean,
) : ViewModel() {
    private val _uiState =
        MutableStateFlow(
            CaptureUiState(
                setup = flow.setup,
                diagnosticsEnabled = diagnosticsEnabled,
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

    fun openSheet() = _uiState.update { it.copy(isSheetOpen = true) }

    fun dismissSheet() = _uiState.update { it.copy(isSheetOpen = false) }

    /**
     * Submits through the flow controller, so the result carries the shape this screen's operation
     * produces. Fabricating one at the button meant Capture always received a stored-method result
     * and reached its transaction screen with no transaction, while the controller was never called.
     */
    fun submit(values: PayInFormValues) {
        // Single flight, decided here. `isSubmitting` disables the button, but only once the state
        // has recomposed, and a second callback landing before that would launch a second capture.
        // Harmless against the demo controller and a duplicate payment against a real one.
        if (_uiState.value.isSubmitting) return
        _uiState.update { it.copy(isSubmitting = true) }
        viewModelScope.launch {
            flow.submit(values).fold(onSuccess = ::onCompleted, onFailure = {
                // A controller that knows what went wrong says so; anything else is unexpected.
                onError(
                    (it as? PaymentFailure)?.error ?: PaymentError.Unexpected(it.message ?: it.javaClass.simpleName),
                )
            })
        }
    }

    fun onCompleted(result: PaymentResult) {
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
                isSheetOpen = false,
                isSubmitting = false,
                outcomeReady = transaction != null,
            )
        }
    }

    fun onError(error: PaymentError) {
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
                isSheetOpen = false,
                isSubmitting = false,
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
                flow = container.captureFlow,
                diagnostics = container.diagnostics.capture,
                diagnosticsEnabled = container.configuration.diagnosticsEnabled,
            )
    }
}
