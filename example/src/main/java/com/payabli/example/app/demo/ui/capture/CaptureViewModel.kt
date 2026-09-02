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
import com.payabli.example.app.sdk.PayInStartup
import com.payabli.example.app.sdk.capturePayment
import com.payabli.example.app.sdk.isBusy
import com.payabli.example.app.sdk.newIdempotencyKey
import com.payabli.example.app.sdk.payInStartup
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import kotlin.coroutines.cancellation.CancellationException

data class CaptureUiState(
    override val setup: PayInFormSetup,
    /**
     * What this attempt charges, fee included.
     *
     * Kept beside [setup] and [operation] because all three describe one figure: the request sends it, the
     * form reads it back, and a retry has to charge the same thing under a different key.
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
    /** A capture of [amount], carrying the key that makes a repeat safe. */
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
    /**
     * The key every reversal of the current transaction sends.
     *
     * One per transaction rather than one per tap. A reversal whose response is lost may have been applied,
     * and only a repeat carrying this key is recognized as that same attempt; a fresh key would ask the
     * service to reverse a transaction it has already reversed, which comes back as a failure over a success.
     * Minted when a payment completes and dropped when the screen moves on.
     */
    val voidIdempotencyKey: String? = null,
) : PaymentFlowUiState {
    /**
     * Its own, because `data class` would synthesize one over the transaction identifier and the key beside it.
     *
     * The readout types this holds each declare theirs for the same reason, and a state that carries them
     * would put back what those took out: this reaches an assertion failure or a crash report whole.
     */
    override fun toString(): String =
        "CaptureUiState(finished=$finished, isVoiding=$isVoiding, hasVoidableTransaction=" +
            "${voidableTransactionId != null})"

    override val finished: Boolean get() = lastResult != null

    /** The transaction a void would name, once there is one and it has not already been reversed. */
    override val voidableTransactionId: String?
        get() =
            lastResult
                ?.transaction
                ?.paymentTransactionId
                ?.takeIf { it != voidedTransactionId }
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
            setup = PayInForms.capture(amount),
            operation =
                capturePayment(
                    idempotencyKey = newIdempotencyKey(),
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
            // The request is built when the screen opens and the switch is on another screen, so a flip after
            // that would otherwise apply to the payment after this one. Rebuilt at the same amount, since the
            // figure on screen is the one the payer was shown. Not while a submission is in flight: replacing
            // the operation then loses the key that makes its retry safe.
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
     * The panel records the failure's classification and nothing from the wire. What a screen shows is
     * displayable and never loggable, because some of it can quote what was submitted, and this panel is on
     * screen and gets copied into bug reports.
     */
    fun onFailed(outcome: PayInOutcome.Refused) {
        rotateIdempotencyKey(outcome)
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
                // One per transaction, minted here so every reversal of it is the same attempt.
                voidIdempotencyKey = transaction?.let { newIdempotencyKey() },
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
                voidIdempotencyKey = null,
            )
        }
    }

    /**
     * Reverses the transaction the last capture returned.
     *
     * Reads the id off the state rather than taking one, so the control cannot name a transaction the screen
     * is not showing. The result replaces the panel's text: a void is what the screen last did, and leaving
     * the capture's text under a reversed transaction is the screen disagreeing with the service.
     *
     * [CaptureUiState.lastResult] is left standing on success, so the identifiers stay on screen and the
     * result step keeps its shape. What stops a second void is [CaptureUiState.voidedTransactionId].
     */
    fun voidLastTransaction() {
        if (_uiState.value.isVoiding) return
        val payments = _uiState.value.payments ?: return
        val transId = _uiState.value.voidableTransactionId ?: return
        val key = _uiState.value.voidIdempotencyKey ?: return
        _uiState.update { it.copy(isVoiding = true) }
        viewModelScope.launch {
            val outcome =
                try {
                    payments.voidTransaction(transId, key)
                } catch (cancellation: CancellationException) {
                    // The flag is cleared on the way out, or the control never comes back.
                    _uiState.update { it.copy(isVoiding = false) }
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
     * The screen a reversal leaves behind.
     *
     * Separate from the call so the branch is a value a test can ask for. Reaching it through
     * [voidLastTransaction] needs a flow answering a real service, which a JVM test cannot build, and the two
     * outcomes differ in more than their wording: only an approval records the transaction as reversed.
     *
     * A refusal leaves [CaptureUiState.voidedTransactionId] alone, because the transaction still stands and
     * the control has to stay available for another try.
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
                    // Reversed, so the control goes and the key has nothing left to identify.
                    voidIdempotencyKey = null,
                    resultText =
                        listOfNotNull(
                            "✓ Voided: ${outcome.result.code}",
                            outcome.result.reason?.let { "Reason: $it" },
                            "Payment transaction: $transId",
                        ).joinToString("\n"),
                )

            is PayInOutcome.Refused ->
                // The key is left alone, whatever the refusal was. A reversal takes only the transaction, so a
                // second attempt is the same request byte for byte and belongs under the same key: it may have
                // been applied and gone unreported, and a fresh key would ask the service to reverse a
                // transaction it has already reversed. The capture above rotates because correcting a rejected
                // field genuinely sends something else. Starting over is what ends this attempt.
                copy(isVoiding = false, resultText = "✗ ${outcome.error.displayMessage}")
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
     *
     * A second payment draws its own amount. The draw has no memory, so it can land on the previous figure
     * again: what tells two rows from one device apart is the order identifier, which carries the second.
     */
    fun startOver() {
        // Synchronously, before anything is replaced. A void in flight is about the transaction this screen is
        // showing, and clearing it here would leave that coroutine writing its outcome over the next attempt:
        // a reversal reported against a payment that had not been taken yet.
        if (_uiState.value.isVoiding) return
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
                // The next payment is a different transaction, so neither what was reversed nor the key that
                // identified a reversal of it carries over.
                voidedTransactionId = null,
                voidIdempotencyKey = null,
            )
        }
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
    private fun rotateIdempotencyKey(outcome: PayInOutcome.Refused) {
        if (outcome.keepsItsIdempotencyKey) return
        _uiState.update { it.copy(operation = attempt(it.amount).operation) }
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
