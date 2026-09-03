package com.payabli.example.app.demo.ui.taptopay

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.payabli.example.app.AppContainer
import com.payabli.example.app.demo.config.DemoConfiguration
import com.payabli.example.app.demo.config.TokenServerTarget
import com.payabli.example.app.demo.net.TokenServerClient
import com.payabli.example.app.demo.net.TokenServerProbe
import com.payabli.example.app.demo.net.displayText
import com.payabli.example.app.demo.preflight.DeviceFacts
import com.payabli.example.app.demo.preflight.PreflightCheck
import com.payabli.example.app.demo.preflight.Readiness
import com.payabli.example.app.demo.preflight.TapToPayPreflight
import com.payabli.example.app.demo.preflight.problemsIn
import com.payabli.example.app.demo.preflight.readinessFrom
import com.payabli.example.app.demo.terminal.EventBuffer
import com.payabli.example.app.demo.terminal.TerminalAction
import com.payabli.example.app.demo.terminal.TerminalActionOutcome
import com.payabli.example.app.demo.terminal.TerminalController
import com.payabli.example.app.demo.terminal.TerminalFailureReason
import com.payabli.example.app.demo.terminal.TerminalSessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TapToPayUiState(
    val configuration: DemoConfiguration,
    val tokenServer: TokenServerTarget,
    val amountText: String = "1.00",
    val activationCode: String = "",
    val resultText: String = "",
    val tokenProbeText: String = "",
    val events: EventBuffer = EventBuffer(),
    val readiness: Readiness = Readiness.Ready,
    val problems: List<PreflightCheck> = emptyList(),
    val session: TerminalSessionState = TerminalSessionState.Idle,
    val isReady: Boolean = false,
    /** Why the session failed, as the SDK named it. Null while it has not. */
    val failureReason: TerminalFailureReason? = null,
    /**
     * Why the last activation attempt was refused, or null if it was not.
     *
     * The reason is captured here, not read from [resultText], which any later action overwrites
     * while this step is still failed. [session] cannot answer either: it reports the same thing
     * for a device that was refused and one that never needed activating.
     */
    val activationFailure: String? = null,
    /** Why the last charge failed. The session reports Ready either way. */
    val chargeFailure: String? = null,
    /** An activation succeeded. [session] reads Ready whether one was needed or not. */
    val activated: Boolean = false,
    val isActivationOpen: Boolean = false,
    /** A token check is running. Narrower than [isWorking], which every terminal action also sets. */
    val isProbingToken: Boolean = false,
    /**
     * Which terminal action is in flight, or null.
     *
     * Which one, not whether: the session reaches [TerminalSessionState.Ready] before the call that
     * took it there returns, so a step reading a bare flag reports itself working over an action
     * belonging to a different step.
     */
    val workingAction: TerminalAction? = null,
) {
    /** Any work at all, which is what disables the controls. */
    val isWorking: Boolean get() = workingAction != null || isProbingToken
}

class TapToPayViewModel(
    private val terminal: TerminalController,
    private val tokenClient: TokenServerClient,
    private val configuration: DemoConfiguration,
    private val readDeviceFacts: () -> DeviceFacts,
    tokenServer: TokenServerTarget,
) : ViewModel() {
    private val _uiState =
        MutableStateFlow(TapToPayUiState(configuration = configuration, tokenServer = tokenServer))
    val uiState: StateFlow<TapToPayUiState> = _uiState.asStateFlow()

    init {
        recheck()
        // viewModelScope, so the subscription lives exactly as long as the screen's place in the back
        // stack. A scope of its own would keep collecting after the screen is gone.
        viewModelScope.launch {
            terminal.events.collect { event ->
                _uiState.update { it.copy(events = it.events.add(event)) }
            }
        }
        viewModelScope.launch {
            terminal.sessionState.collect { state -> _uiState.update { it.copy(session = state) } }
        }
        viewModelScope.launch {
            terminal.failureReason.collect { reason -> _uiState.update { it.copy(failureReason = reason) } }
        }
        viewModelScope.launch {
            terminal.isReady.collect { ready -> _uiState.update { it.copy(isReady = ready) } }
        }
    }

    fun setAmount(text: String) = _uiState.update { it.copy(amountText = text) }

    fun setActivationCode(text: String) = _uiState.update { it.copy(activationCode = text) }

    fun openActivation() = _uiState.update { it.copy(isActivationOpen = true) }

    fun dismissActivation() = _uiState.update { it.copy(isActivationOpen = false) }

    fun clearEvents() = _uiState.update { it.copy(events = it.events.cleared()) }

    fun recheck() {
        val facts = readDeviceFacts()
        val checks = TapToPayPreflight.checks(facts, configuration.appId, configuration.signingCertificate)
        _uiState.update { it.copy(readiness = readinessFrom(checks), problems = problemsIn(checks)) }
    }

    fun enableTerminal() =
        run(TerminalAction.Initialize) {
            terminal.initialize().map { "reader ready" }
        }

    fun reinitialize() =
        run(TerminalAction.Reinitialize) {
            terminal.reinitializeIfNeeded().map { "session is good" }
        }

    fun charge() {
        // Parsed here, so a bad amount produces a message that explains it. Restricting the field's
        // input would leave the keyboard silently refusing a character with no explanation.
        val amount =
            _uiState.value.amountText
                .trim()
                .toBigDecimalOrNull()
        if (amount == null) {
            _uiState.update {
                it.copy(
                    resultText =
                        TerminalActionOutcome.failure(
                            TerminalAction.Charge,
                            NumberFormatException("That is not an amount"),
                        ),
                    // This path returns before `run`, which is the only other thing that records a
                    // charge failure, so without it the step reads "do this next" over a stated one.
                    chargeFailure = "That is not an amount",
                )
            }
            return
        }
        run(TerminalAction.Charge) {
            terminal.charge(amount).map { it.paymentTransactionId }
        }
    }

    fun activate() {
        val code = _uiState.value.activationCode
        _uiState.update { it.copy(isActivationOpen = false) }
        run(TerminalAction.Activate) {
            terminal.activateDevice(code).map { "device activated" }
        }
    }

    fun probeToken() {
        if (_uiState.value.isWorking) return
        _uiState.update { it.copy(tokenProbeText = "Checking…", isProbingToken = true) }
        viewModelScope.launch {
            val outcome = tokenClient.probeAccessToken()
            _uiState.update {
                it.copy(
                    tokenProbeText = outcome.displayText(TokenServerProbe.TOKEN_LABEL),
                    isProbingToken = false,
                )
            }
        }
    }

    /**
     * Runs one terminal action and turns its outcome into the single result line.
     *
     * No spinner: the session chip, the event list and the result line already show progress.
     * `isWorking` only disables the buttons.
     */
    private fun run(
        action: TerminalAction,
        block: suspend () -> Result<String>,
    ) {
        // Single flight, decided here. `isWorking` disables the buttons, but only once the state
        // has recomposed, and a second callback landing before that would run a second action.
        // Two charges would then be in flight, and whichever finished first would re-enable the
        // controls while the other was still running.
        if (_uiState.value.isWorking) return
        _uiState.update { it.copy(workingAction = action) }
        viewModelScope.launch {
            val result = block()
            // Read from the terminal rather than from this state, which is updated by a collector that
            // may not have run yet. A denial is the card reader's refusal and the step list says so; the
            // line has to agree with it.
            val outcome =
                TerminalActionOutcome.from(
                    action,
                    result,
                    readerDenied = terminal.failureReason.value == TerminalFailureReason.DeviceIneligible,
                )
            val reason = outcome.takeIf { result.isFailure }
            _uiState.update {
                it.copy(
                    resultText = outcome,
                    workingAction = null,
                    // Only an activation attempt moves this, either way, so a later failure
                    // elsewhere does not leave the activation step reporting one of its own.
                    activationFailure =
                        if (action == TerminalAction.Activate) reason else it.activationFailure,
                    activated =
                        it.activated || (action == TerminalAction.Activate && result.isSuccess),
                    chargeFailure =
                        if (action == TerminalAction.Charge) reason else it.chargeFailure,
                )
            }
        }
    }

    companion object {
        fun from(container: AppContainer): TapToPayViewModel =
            TapToPayViewModel(
                terminal = container.terminal,
                tokenClient = container.tokenClient,
                configuration = container.configuration,
                readDeviceFacts = container.readDeviceFacts,
                tokenServer = container.tokenServer,
            )
    }
}
