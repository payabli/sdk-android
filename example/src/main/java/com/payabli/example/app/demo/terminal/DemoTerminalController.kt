package com.payabli.example.app.demo.terminal

import com.payabli.example.app.demo.flow.StepStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.math.BigDecimal

/**
 * Stands in for the card-present SDK until it exists.
 *
 * It walks the real state machine and emits the real event codes, so the screen that watches it is
 * exercising the sequence it will see for real: the chip changes four times, the event list fills in
 * order, and the charge button stays disabled until the reader is prepared.
 *
 * Failures are deterministic and reachable without extra controls, so the error paths on screen are
 * live:
 *  - [charge] fails on an amount that is not greater than zero.
 *  - [activateDevice] fails on [REJECTED_ACTIVATION_CODE].
 *
 * The device starts unregistered, so [initialize] stops at [TerminalSessionState.PendingActivation]
 * and the activation step is asked for. Going straight to ready left that step deriving
 * [StepStatus.NotNeeded], which hides its own control, so the activation
 * path and its failure could not be reached by hand.
 *
 * @param stepDelayMillis zero in tests, so the sequence can be asserted without waiting.
 */
class DemoTerminalController(
    private val stepDelayMillis: Long = DEFAULT_STEP_DELAY_MILLIS,
) : TerminalController {
    private val _sessionState = MutableStateFlow(TerminalSessionState.Idle)
    override val sessionState: StateFlow<TerminalSessionState> = _sessionState.asStateFlow()

    // replay = 0: a subscriber that arrives late has missed those events, exactly as it would with a
    // real terminal. extraBufferCapacity keeps emit() from suspending when nothing is listening yet.
    private val _events = MutableSharedFlow<TerminalEvent>(replay = 0, extraBufferCapacity = 64)
    override val events: Flow<TerminalEvent> = _events.asSharedFlow()

    // Its own flow, updated alongside the state. Deriving it with stateIn needs a scope to collect
    // in, and a scope handed in from outside is a coroutine that never completes: it hangs any test
    // that waits for its children, which is every runTest.
    private val _isReady = MutableStateFlow(false)
    override val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    /** Always null: this walks the sequence and has no failure state to reach. */
    override val failureReason: StateFlow<TerminalFailureReason?> = MutableStateFlow(null).asStateFlow()

    private var chargeCounter = 0

    /** A merchant registers a device once. Until then, starting the terminal asks for a code. */
    private var activated = false

    override suspend fun initialize(): Result<Unit> {
        step(TerminalSessionState.AttestingDevice, TerminalEventCode.AttestationStarted)
        emit(TerminalEventCode.AttestationCompleted)
        step(TerminalSessionState.FetchingConfig, TerminalEventCode.ConfigReceived, "entryPoint captured at startup")
        step(TerminalSessionState.InitializingReader, TerminalEventCode.ReaderInitializing)
        if (activated) {
            step(TerminalSessionState.Ready, TerminalEventCode.ReaderReady)
        } else {
            step(TerminalSessionState.PendingActivation, TerminalEventCode.DevicePendingActivation)
        }
        return Result.success(Unit)
    }

    override suspend fun reinitializeIfNeeded(): Result<Unit> {
        if (_sessionState.value == TerminalSessionState.Ready) {
            return Result.success(Unit)
        }
        step(TerminalSessionState.Reinitializing, TerminalEventCode.ReinitializeStarted)
        val outcome = initialize()
        emit(TerminalEventCode.ReinitializeCompleted)
        return outcome
    }

    override suspend fun charge(amount: BigDecimal): Result<ChargeReceipt> {
        if (amount <= BigDecimal.ZERO) {
            return Result.failure(IllegalArgumentException("Enter an amount greater than zero"))
        }
        if (_sessionState.value != TerminalSessionState.Ready) {
            return Result.failure(IllegalStateException("The terminal is not ready"))
        }
        emit(TerminalEventCode.ChargeInitiated, "amount=$amount")
        emit(TerminalEventCode.NfcStarted)
        delay(stepDelayMillis)
        emit(TerminalEventCode.NfcCompleted)
        chargeCounter += 1
        return Result.success(ChargeReceipt("demo-txn-%04d".format(chargeCounter)))
    }

    override suspend fun activateDevice(activationCode: String): Result<Unit> {
        emit(TerminalEventCode.ActivationStarted)
        delay(stepDelayMillis)
        if (activationCode.trim().equals(REJECTED_ACTIVATION_CODE, ignoreCase = true)) {
            emit(TerminalEventCode.ActivationFailed, "code rejected")
            return Result.failure(IllegalArgumentException("That activation code was rejected"))
        }
        emit(TerminalEventCode.ActivationCompleted)
        activated = true
        step(TerminalSessionState.Ready, TerminalEventCode.ReaderReady)
        return Result.success(Unit)
    }

    private suspend fun step(
        state: TerminalSessionState,
        code: TerminalEventCode,
        detail: String = "",
    ) {
        setState(state)
        emit(code, detail)
        delay(stepDelayMillis)
    }

    /** The one place the state moves, so `isReady` cannot fall out of step with it. */
    private fun setState(state: TerminalSessionState) {
        _sessionState.value = state
        _isReady.value = state == TerminalSessionState.Ready
    }

    private suspend fun emit(
        code: TerminalEventCode,
        detail: String = "",
    ) {
        _events.emit(TerminalEvent(code, detail))
    }

    companion object {
        const val DEFAULT_STEP_DELAY_MILLIS: Long = 600

        /** Type this to see the activation failure path. Named in the screen's own help text. */
        const val REJECTED_ACTIVATION_CODE: String = "REJECT"
    }
}
