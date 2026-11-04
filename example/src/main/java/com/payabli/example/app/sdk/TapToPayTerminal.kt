package com.payabli.example.app.sdk

import android.content.Context
import com.payabli.example.app.demo.payment.FailureText
import com.payabli.example.app.demo.sample.SampleIdentity
import com.payabli.example.app.demo.terminal.ChargeReceipt
import com.payabli.example.app.demo.terminal.TerminalController
import com.payabli.example.app.demo.terminal.TerminalEvent
import com.payabli.example.app.demo.terminal.TerminalEventCode
import com.payabli.example.app.demo.terminal.TerminalFailure
import com.payabli.example.app.demo.terminal.TerminalFailureReason
import com.payabli.example.app.demo.terminal.TerminalSessionState
import com.payabli.sdk.core.PayabliSession
import com.payabli.sdk.core.model.PayabliAttempt
import com.payabli.sdk.core.model.PayabliErrorType
import com.payabli.sdk.core.model.PayabliException
import com.payabli.sdk.taptopay.PayabliTTP
import com.payabli.sdk.taptopay.TapToPayCapture
import com.payabli.sdk.taptopay.TapToPayException
import com.payabli.sdk.taptopay.model.TapToPayCustomerData
import com.payabli.sdk.taptopay.model.TapToPayPaymentDetails
import com.payabli.sdk.taptopay.session.TapToPayChargeActivity
import com.payabli.sdk.taptopay.session.TapToPayFailureReason
import com.payabli.sdk.taptopay.session.TapToPaySessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The Tap to pay screen, driven by the card-present SDK.
 *
 * Translates the SDK's states into the screen's, and its phases into the event stream. Built on first
 * use: building it needs a session, which needs the token server to have answered.
 */
class TapToPayTerminal(
    private val appContext: Context,
    private val sessionSource: PayInSessionSource,
    private val entryPoint: String,
    private val scope: CoroutineScope,
    private val identity: SampleIdentity,
) : TerminalController {
    private val _sessionState = MutableStateFlow(TerminalSessionState.Idle)
    override val sessionState: StateFlow<TerminalSessionState> = _sessionState.asStateFlow()

    private val _events = MutableSharedFlow<TerminalEvent>(replay = 0, extraBufferCapacity = 64)
    override val events: Flow<TerminalEvent> = _events.asSharedFlow()

    private val _isReady = MutableStateFlow(false)
    override val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _failureReason = MutableStateFlow<TerminalFailureReason?>(null)
    override val failureReason: StateFlow<TerminalFailureReason?> = _failureReason.asStateFlow()

    /**
     * The line for the error that landed the ineligible reason: set when one is caught, kept
     * while the landing stands, cleared by any other state. The reason decides when it shows.
     */
    private val _deviceFailure = MutableStateFlow<String?>(null)
    override val deviceFailure: StateFlow<String?> = _deviceFailure.asStateFlow()

    /** The amount of the last sale that may have moved money unconfirmed; a charge for it is declared a retry. */
    private var openAmount: BigDecimal? = null

    private val lock = Mutex()
    private var terminal: PayabliTTP? = null
    private var builtFrom: PayabliSession? = null
    private var collecting: Job? = null

    /**
     * A device that owes activation is a handoff, not a failure.
     *
     * `initialize` throws for it, because the reader did not come up. The screen reads that as setup having
     * failed while the step list has already moved on to activation, so the same state was reported two
     * ways at once. The session is what says which happened, and the demo controller answers success here
     * for the same reason.
     */
    override suspend fun initialize(): Result<Unit> =
        attempt(failedAs = null) { terminal().initialize() }
            .recoverCatching { failure ->
                if (terminal?.sessionState?.value !is TapToPaySessionState.PendingActivation) {
                    emit(TerminalEventCode.SetupFailed, failureEventDetail(failure))
                    throw failure
                }
            }

    override suspend fun charge(amount: BigDecimal): Result<ChargeReceipt> =
        attempt(failedAs = TerminalEventCode.UpdateFailed) {
            val ttp = terminal()
            // No NFC events here. `charge` is one call covering the open, the tap and the close, so a
            // pair emitted around it would name the radio and time the whole payment: NfcStarted before
            // the transaction is even opened, NfcCompleted after the closing call returns. The two events
            // below are true of that span. `DemoTerminalController` emits the NFC pair because it stands
            // in for the reader and does know where the tap begins.
            emit(TerminalEventCode.ChargeInitiated, "amount=$amount")
            // The same identity the card-not-present flow charges under. The service refuses a customer
            // object whose three always-present fields are blank, which is what the default carries, so a
            // tap taken without this cannot be opened at all. It also names the device in a dashboard,
            // which is why the card-not-present side has always sent it.
            val receipt =
                try {
                    ttp.charge(
                        TapToPayPaymentDetails(amount),
                        TapToPayCustomerData(
                            firstName = identity.firstName,
                            lastName = identity.lastName,
                            customerNumber = identity.customerNumber,
                        ),
                        attempt = attemptFor(amount, openAmount),
                    )
                } catch (withdrawn: CancellationException) {
                    openAmount = openAmountAfter(openAmount, amount, TapToPayCapture.UNKNOWN)
                    throw withdrawn
                } catch (failure: TapToPayException) {
                    openAmount = openAmountAfter(openAmount, amount, failure.capture)
                    throw failure
                }
            openAmount = openAmountAfter(openAmount, amount, capture = null)
            emit(TerminalEventCode.UpdateCompleted, receipt.cardNetwork.orEmpty())
            ChargeReceipt(receipt.paymentTransId)
        }

    /**
     * Activation leaves the session idle, so this sets the terminal up as well.
     *
     * The setup runs in its own attempt and its failure is not returned. The code is single use and is
     * spent the moment activation succeeds, so folding a later setup failure into this result would
     * report the activation as refused and send someone to spend a code that no longer exists. A setup
     * that fails is on the session state, where the setup step reads it.
     */
    override suspend fun activateDevice(activationCode: String): Result<Unit> {
        val activated =
            attempt(failedAs = TerminalEventCode.ActivationFailed) {
                val ttp = terminal()
                emit(TerminalEventCode.ActivationStarted)
                ttp.activateDevice(activationCode)
                emit(TerminalEventCode.ActivationCompleted)
            }
        if (activated.isFailure) return activated
        initialize()
        return activated
    }

    override fun activationId(): String? =
        (terminal?.sessionState?.value as? TapToPaySessionState.PendingActivation)?.activationId

    /**
     * The SDK for the current session, built again whenever the session is a new one: a terminal belongs to
     * the session it was created from. The state collector starts with it, so the screen sees each phase.
     */
    private suspend fun terminal(): PayabliTTP =
        lock.withLock {
            check(entryPoint.isNotBlank()) { "No entry point is configured, so nothing can be sent." }
            val session = sessionSource.session().getOrThrow()
            terminal?.takeIf { builtFrom === session }
                ?: PayabliTTP.create(session, appContext).also { built ->
                    collecting?.cancel()
                    terminal = built
                    builtFrom = session
                    collecting = scope.launch { built.sessionState.collect { publish(it) } }
                }
        }

    private suspend fun publish(state: TapToPaySessionState) {
        val shown = state.asTerminalState()
        val wasCharging = _sessionState.value.isCharging
        _sessionState.value = shown
        _isReady.value = shown == TerminalSessionState.Ready
        _failureReason.value = (state as? TapToPaySessionState.Failed)?.reason?.asTerminalReason()
        // The line lives as long as the landing does: kept while the reason is ineligible,
        // cleared by any other state, so a stale one cannot read as the current failure's.
        if (_failureReason.value != TerminalFailureReason.DeviceIneligible) _deviceFailure.value = null
        // A charge ending is not the reader coming up.
        if (wasCharging && shown == TerminalSessionState.Ready) return
        state.asEventCode()?.let { emit(it) }
    }

    private fun TapToPayFailureReason.asTerminalReason(): TerminalFailureReason =
        when (this) {
            TapToPayFailureReason.DEVICE_SETUP_REQUIRED -> TerminalFailureReason.DeviceSetupRequired
            TapToPayFailureReason.CONFIGURATION_REJECTED -> TerminalFailureReason.ConfigurationRejected
            TapToPayFailureReason.SERVICE_UNAVAILABLE -> TerminalFailureReason.ServiceUnavailable
            TapToPayFailureReason.DEVICE_INELIGIBLE -> TerminalFailureReason.DeviceIneligible
            TapToPayFailureReason.CONTACTLESS_DISABLED -> TerminalFailureReason.ContactlessDisabled
            TapToPayFailureReason.SDK_INTERNAL_ERROR -> TerminalFailureReason.SdkInternalError
            TapToPayFailureReason.DEVICE_KEY_UNAVAILABLE -> TerminalFailureReason.DeviceKeyUnavailable
            TapToPayFailureReason.SESSION_ENDED -> TerminalFailureReason.SessionEnded
        }

    private fun TapToPaySessionState.asTerminalState(): TerminalSessionState =
        when (this) {
            TapToPaySessionState.Idle -> TerminalSessionState.Idle
            TapToPaySessionState.AttestingDevice -> TerminalSessionState.AttestingDevice
            TapToPaySessionState.FetchingConfig -> TerminalSessionState.FetchingConfig
            TapToPaySessionState.InitializingReader -> TerminalSessionState.InitializingReader
            TapToPaySessionState.Ready -> TerminalSessionState.Ready
            is TapToPaySessionState.Charging ->
                when (activity) {
                    TapToPayChargeActivity.OPENING -> TerminalSessionState.OpeningPayment
                    TapToPayChargeActivity.WAITING_FOR_CARD -> TerminalSessionState.WaitingForCard
                    TapToPayChargeActivity.CLOSING -> TerminalSessionState.ClosingPayment
                }
            TapToPaySessionState.SessionExpired -> TerminalSessionState.SessionExpired
            TapToPaySessionState.Reinitializing -> TerminalSessionState.Reinitializing
            is TapToPaySessionState.PendingActivation -> TerminalSessionState.PendingActivation
            is TapToPaySessionState.Failed -> TerminalSessionState.Error
        }

    /** The phases that have an event of their own. A failure is the result line's to report. */
    private fun TapToPaySessionState.asEventCode(): TerminalEventCode? =
        when (this) {
            TapToPaySessionState.AttestingDevice -> TerminalEventCode.AttestationStarted
            TapToPaySessionState.FetchingConfig -> TerminalEventCode.ConfigReceived
            TapToPaySessionState.InitializingReader -> TerminalEventCode.ReaderInitializing
            TapToPaySessionState.Ready -> TerminalEventCode.ReaderReady
            TapToPaySessionState.SessionExpired -> TerminalEventCode.SessionExpired
            TapToPaySessionState.Reinitializing -> TerminalEventCode.ReinitializeStarted
            is TapToPaySessionState.PendingActivation -> TerminalEventCode.DevicePendingActivation
            TapToPaySessionState.Idle, is TapToPaySessionState.Charging, is TapToPaySessionState.Failed -> null
        }

    /**
     * A withdrawn caller unwinds. Turning it into a failed [Result] reports an error to a screen that left.
     *
     * @param failedAs the event a failure adds to the event list with its code, or null when the caller adds it.
     * @param block the action.
     * @return the action's value, or its failure in the screen's words.
     */
    private suspend fun <T> attempt(
        failedAs: TerminalEventCode?,
        block: suspend () -> T,
    ): Result<T> {
        val failed =
            try {
                return Result.success(block())
            } catch (withdrawn: CancellationException) {
                throw withdrawn
            } catch (failure: PayabliException) {
                failure.ineligibleLine?.let { _deviceFailure.value = it }
                failure.forScreen()
            } catch (failure: Exception) {
                failure
            }
        failedAs?.let { emit(it, failureEventDetail(failed)) }
        return Result.failure(failed)
    }

    private suspend fun emit(
        code: TerminalEventCode,
        detail: String = "",
    ) = _events.emit(TerminalEvent(code, detail))
}

/**
 * The operator's line for a failure, read from a Tap to Pay failure's capture and payment when it is one.
 *
 * @return the failure in this app's words.
 */
internal fun PayabliException.forScreen(): TerminalFailure =
    (this as? TapToPayException).let { forScreen(it?.capture ?: TapToPayCapture.NOT_CHARGED, it?.paymentTransId) }

/**
 * Whether the card was charged is read first: the category alone can say try again after a sale that may be taken.
 * An uncharged failure reads this app's ineligible-device or activation line, else its category's message, never
 * its `reason`, which is the service's text. The code stays on the cause, in the failure's `toString`.
 *
 * @param capture whether the card was charged.
 * @param paymentTransId the payment the failure belongs to, shown with a charged or may-have-been-charged line.
 * @return the failure in this app's words.
 */
internal fun PayabliException.forScreen(
    capture: TapToPayCapture,
    paymentTransId: String?,
): TerminalFailure =
    TerminalFailure(
        chargeLine(capture, paymentTransId) ?: ineligibleLine ?: activationLine
            ?: FailureText.terminal(toPaymentError().kind),
        this,
        code.toString(),
    )

private fun chargeLine(
    capture: TapToPayCapture,
    paymentTransId: String?,
): String? {
    val line =
        when (capture) {
            TapToPayCapture.CHARGED -> "The card was charged. Don't charge it again."
            TapToPayCapture.UNKNOWN -> "This payment may have gone through. Check before charging again."
            TapToPayCapture.NOT_CHARGED -> return null
        }
    return if (paymentTransId == null) line else "$line · txn $paymentTransId"
}

/**
 * This app's own line for a refused activation, naming what the operator does next, or null for any other error.
 * The category alone would give six refusals with six different remedies one line.
 */
internal val PayabliException.activationLine: String?
    get() =
        when (type) {
            PayabliErrorType.ACTIVATION_CODE_MALFORMED -> "Enter the six-digit activation code."
            PayabliErrorType.ACTIVATION_CODE_INCORRECT ->
                "That activation code isn't right. Check it and enter it again."
            PayabliErrorType.ACTIVATION_CODE_EXPIRED -> "That activation code has expired. Get a new code and enter it."
            PayabliErrorType.ACTIVATION_ATTEMPTS_EXHAUSTED ->
                "Too many wrong codes were entered. Get a new code and enter it."
            PayabliErrorType.ACTIVATION_CODE_NOT_ISSUED ->
                "No activation code has been issued for this phone. Get a code and enter it."
            PayabliErrorType.DEVICE_NOT_PENDING -> "This phone isn't waiting for activation. Set up the terminal again."
            else -> null
        }

/**
 * The event list's detail for a failure: its catalog code, or the class name of a failure the SDK did not raise.
 *
 * @param failure the failure, before or after [forScreen].
 * @return the detail, never the failure's text.
 */
internal fun failureEventDetail(failure: Throwable): String =
    "code=" + ((failure as? TerminalFailure)?.code ?: failure.toPaymentError().code)

/**
 * This app's own line for an error whose landing is the ineligible reason, or null when it landed another.
 *
 * Four error codes land that one reason, so the line is chosen by the error's type. The code stays on the
 * cause, in the failure's `toString`.
 */
internal val PayabliException.ineligibleLine: String?
    get() =
        when (type) {
            PayabliErrorType.DEVICE_OS_UNSUPPORTED -> "This phone's Android version is too old for Tap to Pay."
            PayabliErrorType.DEVICE_HARDWARE_UNSUPPORTED -> "This phone can't take contactless payments."
            PayabliErrorType.READER_DEVICE_REFUSED ->
                "This device isn't set up for Tap to Pay. Contact Payabli support with the device ID."
            PayabliErrorType.DEVICE_IDENTITY_UNAVAILABLE -> "This phone couldn't be identified for Tap to Pay."
            else -> null
        }

/**
 * A charge for the open sale's amount, compared in cents as the SDK charges it, so `1.0`, `1.00` and `1.001`
 * are one sale, is its retry.
 *
 * @return [PayabliAttempt.Retry] for the open amount, [PayabliAttempt.NewPayment] otherwise.
 */
internal fun attemptFor(
    amount: BigDecimal,
    open: BigDecimal?,
): PayabliAttempt =
    if (open != null &&
        amount.inCents().compareTo(open.inCents()) == 0
    ) {
        PayabliAttempt.Retry
    } else {
        PayabliAttempt.NewPayment
    }

private fun BigDecimal.inCents(): BigDecimal = setScale(2, RoundingMode.HALF_UP)

/**
 * The open amount once a charge ends: [capture] null for a success, which settles only its own amount.
 *
 * @return [amount] when money may have moved unconfirmed, null once that sale succeeded, [open] otherwise.
 */
internal fun openAmountAfter(
    open: BigDecimal?,
    amount: BigDecimal,
    capture: TapToPayCapture?,
): BigDecimal? =
    when (capture) {
        TapToPayCapture.UNKNOWN, TapToPayCapture.CHARGED -> amount
        TapToPayCapture.NOT_CHARGED -> open
        null -> open.takeUnless { attemptFor(amount, it) == PayabliAttempt.Retry }
    }
