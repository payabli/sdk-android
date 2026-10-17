package com.payabli.example.app.demo.ui.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.payabli.example.app.AppContainer
import com.payabli.example.app.demo.config.DemoConfiguration
import com.payabli.example.app.demo.diagnostics.DiagnosticsStore
import com.payabli.example.app.demo.net.checkToken
import com.payabli.example.app.demo.payment.PaymentError
import com.payabli.example.app.demo.payment.PaymentResult
import com.payabli.example.app.demo.sample.DemoCustomerSetting
import com.payabli.example.app.demo.sample.SampleAmount
import com.payabli.example.app.demo.sample.SampleIdentity
import com.payabli.example.app.demo.ui.payment.PaymentFlowUiState
import com.payabli.example.app.sdk.PayInFlowHandle
import com.payabli.example.app.sdk.PayInFormSetup
import com.payabli.example.app.sdk.PayInForms
import com.payabli.example.app.sdk.PayInOperation
import com.payabli.example.app.sdk.PayInOutcome
import com.payabli.example.app.sdk.PayInReadOutcome
import com.payabli.example.app.sdk.PayInStartup
import com.payabli.example.app.sdk.capturePayment
import com.payabli.example.app.sdk.isBusy
import com.payabli.example.app.sdk.newPaymentReference
import com.payabli.example.app.sdk.payInStartup
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import kotlin.coroutines.cancellation.CancellationException

/**
 * What the capture screen shows.
 *
 * @property voidOutcomeUnknown whether a void of the current transaction ended with its outcome unknown. The
 *   control is withheld until a read shows the transaction voided or still captured.
 * @property voidKeyHeld whether the flow may still hold a void key for the current transaction, which a read
 *   showing it still captured does not release, so a token check keeps that flow.
 */
data class CaptureUiState(
    override val setup: PayInFormSetup,
    /**
     * What this attempt charges, fee included.
     *
     * Kept beside [setup] and [operation] because all three describe one figure: the request sends it, the
     * form reads it back, and a retry has to charge the same thing as the payment it names.
     */
    val amount: BigDecimal,
    override val sampleIdentity: SampleIdentity,
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
    val payments: PayInFlowHandle? = null,
    /** A capture of [amount], naming the payment so a repeat of it is safe. */
    val operation: PayInOperation,
    /**
     * A void is in flight.
     *
     * Its own flag rather than the flow's state: the SDK publishes a void nowhere, because nothing is
     * drawing it, so `payments.isSubmitting()` stays false throughout one.
     */
    override val isVoiding: Boolean = false,
    /** The transaction this screen has already reversed, so the control cannot offer it twice. */
    val voidedTransactionId: String? = null,
    val voidOutcomeUnknown: Boolean = false,
    val voidKeyHeld: Boolean = false,
    override val isReading: Boolean = false,
) : PaymentFlowUiState {
    /**
     * Its own, because `data class` would synthesize one over the transaction identifier and the payment's reference.
     *
     * The readout types this holds each declare theirs for the same reason, and a state that carries them
     * would put back what those took out: this reaches an assertion failure or a crash report whole.
     */
    override fun toString(): String =
        "CaptureUiState(finished=$finished, isVoiding=$isVoiding, hasVoidableTransaction=" +
            "${voidableTransactionId != null})"

    override val finished: Boolean get() = lastResult != null

    /** The last capture's transaction, reversed or not. */
    override val readableTransactionId: String?
        get() = lastResult?.transaction?.paymentTransactionId

    /** The transaction a void would name, once there is one, it is not reversed and no void of it is in doubt. */
    override val voidableTransactionId: String?
        get() =
            lastResult
                ?.transaction
                ?.paymentTransactionId
                ?.takeIf { it != voidedTransactionId && !voidOutcomeUnknown }
}

/**
 * Scoped to the capture graph, so the result screen reads the same instance the form screen wrote
 * to. A route carries no arbitrary API response.
 */
class CaptureViewModel(
    private val identity: SampleIdentity,
    private val demoCustomer: DemoCustomerSetting,
    private val startup: PayInStartup,
    private val diagnostics: DiagnosticsStore,
    private val diagnosticsEnabled: Boolean,
    private val configuration: DemoConfiguration,
) : ViewModel() {
    private val _uiState =
        MutableStateFlow(
            attempt(SampleAmount.random()).let { attempt ->
                CaptureUiState(
                    setup = attempt.setup,
                    amount = attempt.amount,
                    operation = attempt.operation,
                    sampleIdentity = identity,
                    diagnosticsEnabled = diagnosticsEnabled,
                    prefillEnabled = configuration.prefillEnabled,
                    entryPoint = configuration.entryPoint,
                    host = configuration.environment.host,
                )
            },
        )
    val uiState: StateFlow<CaptureUiState> = _uiState.asStateFlow()

    /** The three values that have to agree about one figure, built in one place so they cannot disagree. */
    private class Attempt(
        val amount: BigDecimal,
        val setup: PayInFormSetup,
        val operation: PayInOperation,
    )

    /**
     * @param amount fee included. Fresh for a new payment, the previous one's for a retry, because a payer
     *   correcting a rejected field is being charged the figure they were shown.
     */
    private fun attempt(amount: BigDecimal): Attempt =
        Attempt(
            amount = amount,
            setup = PayInForms.capture(),
            operation =
                capturePayment(
                    paymentReference = newPaymentReference(),
                    amount = amount,
                    identity = identity,
                    atMillis = System.currentTimeMillis(),
                    suppliesDemoCustomer = demoCustomer.suppliesDemoCustomer.value,
                ),
        )

    init {
        viewModelScope.launch {
            diagnostics.messages.collect { messages ->
                _uiState.update { it.copy(diagnostics = messages) }
            }
        }
        viewModelScope.launch {
            // Rebuilt at the same amount when the switch flips, so it applies to this payment, not the next.
            // Not while a submission is in flight: replacing the operation then loses the reference its retry needs.
            demoCustomer.suppliesDemoCustomer.collect {
                _uiState.update { state ->
                    if (state.payments.isBusy()) state else state.copy(operation = attempt(state.amount).operation)
                }
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
                    // A flow holding a payment or its outcome is kept, and so is one holding a reversal that
                    // is running or unresolved, since the next reversal has to resend that flow's key.
                    payments =
                        it.payments?.takeIf { flow -> flow.isBusy() || it.isVoiding || it.voidKeyHeld }
                            ?: started.payments,
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
     * The panel records the failure's classification and nothing from the wire. What a screen shows is
     * displayable and never loggable, because some of it can quote what was submitted, and this panel is on
     * screen and gets copied into bug reports.
     */
    fun onFailed(outcome: PayInOutcome.Refused) {
        rotatePaymentReference(outcome)
        record("ERROR paymentTransaction\n${outcome.diagnostic}")
        onError(outcome.error)
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
                voidOutcomeUnknown = false,
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

    /**
     * Voids the transaction on screen. A void whose outcome is unknown is not repeated until a read shows the
     * transaction still captured; the SDK then sends the same void again, so the service recognises it.
     */
    fun voidLastTransaction() {
        // A read in flight writes the result panel when it returns, and would overwrite this void's outcome.
        if (_uiState.value.isVoiding || _uiState.value.isReading) return
        val payments = _uiState.value.payments ?: return
        val transId = _uiState.value.voidableTransactionId ?: return
        _uiState.update { it.copy(isVoiding = true) }
        viewModelScope.launch {
            val outcome =
                try {
                    payments.voidTransaction(transId)
                } catch (cancellation: CancellationException) {
                    // The flag is cleared on the way out, or the controls never come back. The void may have gone
                    // out, so it is withheld as an unknown outcome is.
                    _uiState.update { it.copy(isVoiding = false, voidOutcomeUnknown = true, voidKeyHeld = true) }
                    throw cancellation
                }
            record(
                when (outcome) {
                    is PayInOutcome.Approved -> "RESPONSE void\ncode=${outcome.result.code}"
                    is PayInOutcome.Refused -> "ERROR void\n${outcome.diagnostic}"
                },
            )
            _uiState.update { it.afterVoiding(outcome, transId) }
        }
    }

    /**
     * Reads back the transaction the last capture returned, and shows its status.
     *
     * Takes the ID from the state, as [voidLastTransaction] does, so it reads only the transaction on screen.
     */
    fun readLastTransaction() {
        if (_uiState.value.isReading || _uiState.value.isVoiding) return
        val payments = _uiState.value.payments ?: return
        val transId = _uiState.value.readableTransactionId ?: return
        _uiState.update { it.copy(isReading = true) }
        viewModelScope.launch {
            val outcome =
                try {
                    payments.readTransaction(transId)
                } catch (cancellation: CancellationException) {
                    _uiState.update { it.copy(isReading = false) }
                    throw cancellation
                }
            _uiState.update { it.afterReading(outcome, transId) }
        }
    }

    /**
     * The state after a read: the status on success, the reason on failure. A read that reports the transaction
     * voided marks it reversed, as an approved void does. After a void whose outcome is unknown, a read that
     * reports it captured offers the void again, and any other status leaves it withheld.
     *
     * @param outcome what the read returned.
     * @param transId the transaction that was read.
     * @return the state with the read's text in the result panel.
     */
    internal fun CaptureUiState.afterReading(
        outcome: PayInReadOutcome,
        transId: String,
    ): CaptureUiState =
        copy(
            isReading = false,
            voidedTransactionId = if (outcome.isVoidOf()) transId else voidedTransactionId,
            voidOutcomeUnknown = voidOutcomeUnknown && !outcome.settlesAVoid(),
            voidKeyHeld = voidKeyHeld && !outcome.isVoidOf(),
            resultText =
                when (outcome) {
                    is PayInReadOutcome.Read ->
                        listOfNotNull(
                            "Status: ${outcome.status}",
                            outcome.statusNumber?.let { "Status number: $it" },
                            outcome.paymentTransactionId?.let { "Payment transaction: $it" },
                        ).joinToString("\n")
                    is PayInReadOutcome.Failed -> "✗ ${outcome.error.displayMessage}"
                },
        )

    private fun PayInReadOutcome.isVoidOf(): Boolean = this is PayInReadOutcome.Read && isVoided

    private fun PayInReadOutcome.settlesAVoid(): Boolean = this is PayInReadOutcome.Read && (isVoided || isCaptured)

    /**
     * The state after a void: an approval marks the transaction reversed, and a refusal whose outcome is unknown
     * withholds the control and says to check the status.
     *
     * @param outcome what the void returned.
     * @param transId the transaction that was voided.
     * @return the state with the void's result in the result panel.
     */
    internal fun CaptureUiState.afterVoiding(
        outcome: PayInOutcome,
        transId: String,
    ): CaptureUiState =
        when (outcome) {
            is PayInOutcome.Approved ->
                copy(
                    isVoiding = false,
                    voidedTransactionId = transId,
                    voidKeyHeld = false,
                    resultText =
                        listOfNotNull(
                            "✓ Voided: ${outcome.result.code}",
                            outcome.result.reason?.let { "Reason: $it" },
                            "Payment transaction: $transId",
                        ).joinToString("\n"),
                )

            is PayInOutcome.Refused ->
                copy(
                    isVoiding = false,
                    voidOutcomeUnknown = outcome.isOutcomeUnknown,
                    voidKeyHeld = outcome.isOutcomeUnknown,
                    resultText =
                        listOfNotNull(
                            "✗ ${outcome.error.displayMessage}",
                            when {
                                outcome.isRetryRefused ->
                                    "The earlier reversal may still be running. Read this transaction again shortly."
                                outcome.isOutcomeUnknown ->
                                    "Check this transaction's status to see whether it was voided."
                                else -> null
                            },
                        ).joinToString("\n"),
                )
        }

    /** Records only when diagnostics are on, so the setting governs what is kept and not only what is shown. */
    private fun record(line: String) {
        if (diagnosticsEnabled) diagnostics.record(line)
    }

    /** Cleared once navigation has happened, so returning to this screen does not push again. */
    fun outcomeShown() = _uiState.update { it.copy(outcomeReady = false) }

    /**
     * Back to the form step for a second payment, which needs its own reference. The amount is drawn again, and
     * the order identifier is what tells two payments of one figure apart.
     */
    fun startOver() {
        // Checked before anything is replaced: a void or read in flight would write its outcome over the next
        // payment.
        if (_uiState.value.isVoiding || _uiState.value.isReading) return
        _uiState.update {
            val attempt = attempt(SampleAmount.random())
            it.copy(
                resultText = "",
                submitFailed = false,
                lastResult = null,
                outcomeReady = false,
                setup = attempt.setup,
                amount = attempt.amount,
                operation = attempt.operation,
                // The next payment is a different transaction, so nothing about voiding this one carries over.
                voidedTransactionId = null,
                voidOutcomeUnknown = false,
                voidKeyHeld = false,
            )
        }
    }

    /**
     * Names a new payment once the service has answered this one. An unknown outcome keeps the name and declares
     * the next submission its retry; `AlreadySubmitting` changes nothing, since that request is still running.
     */
    private fun rotatePaymentReference(outcome: PayInOutcome.Refused) {
        when {
            outcome.isOutcomeUnknown -> _uiState.update { it.copy(operation = it.operation.asRetry()) }
            outcome.keepsItsPaymentReference -> return
            else -> _uiState.update { it.copy(operation = attempt(it.amount).operation) }
        }
    }

    companion object {
        fun from(container: AppContainer): CaptureViewModel =
            CaptureViewModel(
                identity = container.sampleIdentity,
                demoCustomer = container.demoCustomer,
                startup = container.payInStartup,
                diagnostics = container.diagnostics.capture,
                diagnosticsEnabled = container.configuration.diagnosticsEnabled,
                configuration = container.configuration,
            )
    }
}
