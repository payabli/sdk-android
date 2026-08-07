package com.payabli.example.app.ui.method

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

data class PaymentMethodUiState(
    val configuration: PaymentFormConfiguration,
    val resultText: String = "",
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
                configuration = flow.configuration,
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
        _uiState.update { it.copy(resultText = text, isSheetOpen = false, isSubmitting = false) }
    }

    fun onError(error: PaymentError) {
        record("ERROR paymentMethod\n${error.displayMessage}")
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

    companion object {
        fun from(container: AppContainer): PaymentMethodViewModel =
            PaymentMethodViewModel(
                flow = container.paymentMethodFlow,
                diagnostics = container.diagnostics.paymentMethod,
                diagnosticsEnabled = container.configuration.diagnosticsEnabled,
            )
    }
}
