package com.payabli.example.app.demo.ui.method

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.payabli.example.app.AppContainer
import com.payabli.example.app.demo.config.DemoConfiguration
import com.payabli.example.app.demo.diagnostics.DiagnosticsStore
import com.payabli.example.app.demo.net.checkToken
import com.payabli.example.app.demo.payment.PaymentError
import com.payabli.example.app.demo.payment.PaymentResult
import com.payabli.example.app.demo.payment.StoredMethod
import com.payabli.example.app.demo.sample.SampleIdentity
import com.payabli.example.app.demo.ui.payment.PaymentFlowUiState
import com.payabli.example.app.sdk.PayInFlowHandle
import com.payabli.example.app.sdk.PayInFormSetup
import com.payabli.example.app.sdk.PayInForms
import com.payabli.example.app.sdk.PayInOperation
import com.payabli.example.app.sdk.PayInOutcome
import com.payabli.example.app.sdk.PayInStartup
import com.payabli.example.app.sdk.isBusy
import com.payabli.example.app.sdk.payInStartup
import com.payabli.example.app.sdk.storePaymentMethod
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

data class PaymentMethodUiState(
    override val setup: PayInFormSetup,
    override val sampleIdentity: SampleIdentity,
    override val resultText: String = "",
    /** Raised only when the completion carried the payload this screen exists to show. */
    val outcomeReady: Boolean = false,
    /** What was stored, held so the pushed screen can check it is still describing something. */
    val storedMethod: StoredMethod? = null,
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
    val payments: PayInFlowHandle? = null,
    /** Storing an instrument, which carries no amount. */
    val operation: PayInOperation =
        storePaymentMethod(),
) : PaymentFlowUiState {
    /**
     * Its own, for the reason `StoredMethod` declares one: `resultText` quotes the identifier a later
     * transaction charges, so a synthesized `toString` would carry it into anything that stringifies this.
     */
    override fun toString(): String = "PaymentMethodUiState(finished=$finished)"

    override val finished: Boolean get() = storedMethod != null
}

class PaymentMethodViewModel(
    setup: PayInFormSetup,
    identity: SampleIdentity,
    private val startup: PayInStartup,
    private val diagnostics: DiagnosticsStore,
    private val diagnosticsEnabled: Boolean,
    private val configuration: DemoConfiguration,
) : ViewModel() {
    private val _uiState =
        MutableStateFlow(
            PaymentMethodUiState(
                setup = setup,
                sampleIdentity = identity,
                diagnosticsEnabled = diagnosticsEnabled,
                prefillEnabled = configuration.prefillEnabled,
                entryPoint = configuration.entryPoint,
                host = configuration.environment.host,
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
    fun onCompleted(outcome: PayInOutcome.Approved) {
        onCompleted(outcome.result)
    }

    /**
     * The SDK refused it.
     *
     * What the panel records is the exception's own `toString`, which carries the error code and nothing from
     * the wire. `reason` and `detail` are displayable and never loggable, because either can quote what was
     * submitted, and this panel is on screen and gets copied into bug reports.
     */
    fun onFailed(outcome: PayInOutcome.Refused) {
        record("ERROR paymentMethod\n${outcome.diagnostic}")
        onError(outcome.error)
    }

    private fun onCompleted(result: PaymentResult) {
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
        record("RESPONSE paymentMethod\ncode=${result.code}")
        _uiState.update {
            it.copy(
                resultText = text,
                storedMethod = method,
                // A response can arrive carrying nothing, which the text above calls a failure. The
                // flag has to agree, or the sequence says "do this next" over a stated failure.
                submitFailed = method == null,
                isSheetOpen = false,
                outcomeReady = method != null,
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
            // "Payment method saved" screen on top of the error just recorded, and that screen reads
            // storedMethod to decide it still has something to describe.
            it.copy(
                resultText = "✗ ${error.displayMessage}",
                outcomeReady = false,
                storedMethod = null,
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
     * Back to the form step, for another method.
     *
     * A finished step draws no controls, so the form leaves the screen once a method is stored and this is the
     * only way back to it.
     */
    fun startOver() =
        _uiState.update {
            it.copy(resultText = "", submitFailed = false, storedMethod = null, outcomeReady = false)
        }

    companion object {
        fun from(container: AppContainer): PaymentMethodViewModel =
            PaymentMethodViewModel(
                setup = PayInForms.storePaymentMethod(),
                identity = container.sampleIdentity,
                startup = container.payInStartup,
                diagnostics = container.diagnostics.paymentMethod,
                diagnosticsEnabled = container.configuration.diagnosticsEnabled,
                configuration = container.configuration,
            )
    }
}
