package com.payabli.example.app.sdk

import android.content.Context
import com.payabli.example.app.demo.terminal.ChargeReceipt
import com.payabli.example.app.demo.terminal.TerminalController
import com.payabli.example.app.demo.terminal.TerminalEvent
import com.payabli.example.app.demo.terminal.TerminalEventCode
import com.payabli.example.app.demo.terminal.TerminalFailureReason
import com.payabli.example.app.demo.terminal.TerminalSessionState
import com.payabli.sdk.taptopay.PayabliTTP
import com.payabli.sdk.taptopay.model.TapToPayPaymentDetails
import com.payabli.sdk.taptopay.session.TapToPayFailureReason
import com.payabli.sdk.taptopay.session.TapToPaySessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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

/**
 * The Tap to pay screen, driven by the card-present SDK.
 *
 * Translates the SDK's nine states into the screen's, and its phases into the event stream. Built on first
 * use: building it needs a session, which needs the token server to have answered.
 */
class TapToPayTerminal(
    private val appContext: Context,
    private val sessionSource: PayInSessionSource,
    private val entryPoint: String,
    private val cloudProjectNumber: Long?,
    private val scope: CoroutineScope,
) : TerminalController {
    private val _sessionState = MutableStateFlow(TerminalSessionState.Idle)
    override val sessionState: StateFlow<TerminalSessionState> = _sessionState.asStateFlow()

    private val _events = MutableSharedFlow<TerminalEvent>(replay = 0, extraBufferCapacity = 64)
    override val events: Flow<TerminalEvent> = _events.asSharedFlow()

    private val _isReady = MutableStateFlow(false)
    override val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _failureReason = MutableStateFlow<TerminalFailureReason?>(null)
    override val failureReason: StateFlow<TerminalFailureReason?> = _failureReason.asStateFlow()

    private val lock = Mutex()
    private var terminal: PayabliTTP? = null

    override suspend fun initialize(): Result<Unit> = attempt { terminal().initialize() }

    override suspend fun reinitializeIfNeeded(): Result<Unit> =
        attempt {
            terminal().reinitializeIfNeeded()
            emit(TerminalEventCode.ReinitializeCompleted)
        }

    override suspend fun charge(amount: BigDecimal): Result<ChargeReceipt> =
        attempt {
            val ttp = terminal()
            emit(TerminalEventCode.ChargeInitiated, "amount=$amount")
            emit(TerminalEventCode.NfcStarted)
            val receipt = ttp.charge(TapToPayPaymentDetails(amount))
            emit(TerminalEventCode.NfcCompleted, receipt.cardNetwork.orEmpty())
            emit(TerminalEventCode.UpdateCompleted)
            ChargeReceipt(receipt.paymentTransId)
        }

    /** Activation leaves the session idle, so this sets the terminal up as well. */
    override suspend fun activateDevice(activationCode: String): Result<Unit> =
        attempt {
            val ttp = terminal()
            emit(TerminalEventCode.ActivationStarted)
            ttp.activateDevice(activationCode)
            emit(TerminalEventCode.ActivationCompleted)
            ttp.initialize()
        }

    /** The SDK, built once. The state collector starts with it, so the screen sees each phase. */
    private suspend fun terminal(): PayabliTTP =
        lock.withLock {
            terminal ?: build().also { built ->
                terminal = built
                scope.launch { built.sessionState.collect { publish(it) } }
            }
        }

    private suspend fun build(): PayabliTTP {
        check(entryPoint.isNotBlank()) { "No entry point is configured, so nothing can be sent." }
        check(cloudProjectNumber != null) {
            "payabli.cloudProjectNumber is not set, and a build installed by hand needs it to attest."
        }
        return PayabliTTP.create(
            session = sessionSource.session().getOrThrow(),
            context = appContext,
            entryPoint = entryPoint,
            cloudProjectNumber = cloudProjectNumber,
        )
    }

    override fun currentFailureReason(): TerminalFailureReason? =
        (terminal?.sessionState?.value as? TapToPaySessionState.Failed)?.reason?.asTerminalReason()

    private suspend fun publish(state: TapToPaySessionState) {
        val shown = state.asTerminalState()
        _sessionState.value = shown
        _isReady.value = shown == TerminalSessionState.Ready
        _failureReason.value = (state as? TapToPaySessionState.Failed)?.reason?.asTerminalReason()
        state.asEventCode()?.let { emit(it) }
    }

    private fun TapToPayFailureReason.asTerminalReason(): TerminalFailureReason =
        when (this) {
            TapToPayFailureReason.ATTESTATION_REQUIRED -> TerminalFailureReason.AttestationRequired
            TapToPayFailureReason.CONFIGURATION_REJECTED -> TerminalFailureReason.ConfigurationRejected
            TapToPayFailureReason.SERVICE_UNAVAILABLE -> TerminalFailureReason.ServiceUnavailable
            TapToPayFailureReason.DEVICE_INELIGIBLE -> TerminalFailureReason.DeviceIneligible
            TapToPayFailureReason.SDK_INTERNAL_ERROR -> TerminalFailureReason.SdkInternalError
        }

    private fun TapToPaySessionState.asTerminalState(): TerminalSessionState =
        when (this) {
            TapToPaySessionState.Idle -> TerminalSessionState.Idle
            TapToPaySessionState.AttestingDevice -> TerminalSessionState.AttestingDevice
            TapToPaySessionState.FetchingConfig -> TerminalSessionState.FetchingConfig
            TapToPaySessionState.InitializingReader -> TerminalSessionState.InitializingReader
            TapToPaySessionState.Ready -> TerminalSessionState.Ready
            TapToPaySessionState.SessionExpired -> TerminalSessionState.SessionExpired
            TapToPaySessionState.Reinitializing -> TerminalSessionState.Reinitializing
            TapToPaySessionState.PendingActivation -> TerminalSessionState.PendingActivation
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
            TapToPaySessionState.PendingActivation -> TerminalEventCode.DevicePendingActivation
            TapToPaySessionState.Idle, is TapToPaySessionState.Failed -> null
        }

    /** A withdrawn caller unwinds. Turning it into a failed [Result] reports an error to a screen that left. */
    private suspend fun <T> attempt(block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (withdrawn: CancellationException) {
            throw withdrawn
        } catch (failure: Exception) {
            Result.failure(failure)
        }

    private suspend fun emit(
        code: TerminalEventCode,
        detail: String = "",
    ) = _events.emit(TerminalEvent(code, detail))
}
