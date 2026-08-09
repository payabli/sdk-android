package com.payabli.example.app.ui.method

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.payabli.example.app.AppContainer
import com.payabli.example.app.diagnostics.DiagnosticsStore
import com.payabli.example.app.payment.DemoFormSetup
import com.payabli.example.app.payment.PaymentError
import com.payabli.example.app.payment.PaymentFlowController
import com.payabli.example.app.payment.PaymentResult
import com.payabli.example.app.payment.StoredMethod
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PaymentMethodUiState(
    val setup: DemoFormSetup,
    val resultText: String = "",
    /** Raised only when the completion carried the payload this screen exists to show. */
    val outcomeReady: Boolean = false,
    /** What was stored, held so the pushed screen can check it is still describing something. */
    val storedMethod: StoredMethod? = null,
    val diagnostics: List<String> = emptyList(),
    val diagnosticsEnabled: Boolean = true,
    val isSheetOpen: Boolean = false,
    val isSubmitting: Boolean = false,
)

class PaymentMethodViewModel(
    private val flow: PaymentFlowController,
    private val diagnostics: DiagnosticsStore,
    private val diagnosticsEnabled: Boolean,
) : ViewModel() {
    private val _uiState =
        MutableStateFlow(
            PaymentMethodUiState(
                setup = flow.setup,
                diagnosticsEnabled = diagnosticsEnabled,
            ),
        )
    val uiState: StateFlow<PaymentMethodUiState> = _uiState.asStateFlow()

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
        // Single flight, decided here. `isSubmitting` disables the button, but only once the state
        // has recomposed, and a second callback landing before that would launch a second request.
        // Harmless against the demo controller and a duplicate stored instrument against a real one.
        if (_uiState.value.isSubmitting) return
        _uiState.update { it.copy(isSubmitting = true) }
        viewModelScope.launch {
            flow.submit().fold(onSuccess = ::onCompleted, onFailure = {
                onError(PaymentError.Unexpected(it.message ?: it.javaClass.simpleName))
            })
        }
    }

    fun onCompleted(result: PaymentResult) {
        val method = result.storedMethod
        val text =
            if (method == null) {
                "✗ The response carried no stored method."
            } else {
                // Marked, because success and failure land in the same card. Without a glyph a
                // decline reads exactly like a stored method, which is the one thing this card
                // must never do. The Tap to pay screen has always done this; these two had not.
                listOf(
                    "✓ Stored method: ${method.storedMethodId}",
                    "Response: ${method.responseText}",
                    "Result: ${method.resultText}",
                ).joinToString("\n")
            }
        record("RESPONSE ${result.code} paymentMethod\nreason=${result.reason}")
        _uiState.update {
            it.copy(
                resultText = text,
                storedMethod = method,
                isSheetOpen = false,
                isSubmitting = false,
                outcomeReady = method != null,
            )
        }
    }

    fun onError(error: PaymentError) {
        record("ERROR paymentMethod\n${error.displayMessage}")
        _uiState.update {
            // The outcome signal and its payload are cleared, not left standing. A failure arriving
            // after a completion but before navigation consumed the signal would otherwise push the
            // "Payment method saved" screen on top of the error just recorded, and that screen reads
            // storedMethod to decide it still has something to describe.
            it.copy(
                resultText = "✗ ${error.displayMessage}",
                outcomeReady = false,
                storedMethod = null,
                isSheetOpen = false,
                isSubmitting = false,
            )
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
        fun from(container: AppContainer): PaymentMethodViewModel =
            PaymentMethodViewModel(
                flow = container.paymentMethodFlow,
                diagnostics = container.diagnostics.paymentMethod,
                diagnosticsEnabled = container.configuration.diagnosticsEnabled,
            )
    }
}
