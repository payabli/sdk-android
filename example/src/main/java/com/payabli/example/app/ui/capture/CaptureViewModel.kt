package com.payabli.example.app.ui.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.payabli.example.app.AppContainer
import com.payabli.example.app.diagnostics.DiagnosticsStore
import com.payabli.example.app.payment.PaymentError
import com.payabli.example.app.payment.PaymentFlowController
import com.payabli.example.app.payment.PaymentFormConfiguration
import com.payabli.example.app.payment.PaymentResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CaptureUiState(
    val configuration: PaymentFormConfiguration,
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
                configuration = flow.configuration,
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
    fun submit() {
        _uiState.update { it.copy(isSubmitting = true) }
        viewModelScope.launch {
            flow.submit().fold(onSuccess = ::onCompleted, onFailure = {
                onError(PaymentError.Unexpected(it.message ?: it.javaClass.simpleName))
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
            it.copy(resultText = "✗ ${error.displayMessage}", isSheetOpen = false, isSubmitting = false)
        }
    }

    /**
     * Records only when diagnostics are on.
     *
     * The flag previously reached the UI state and nothing else, so turning diagnostics off hid the
     * panel while every entry was still recorded and retained. A setting that changes what is shown
     * and not what is kept is the wrong half.
     */

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
