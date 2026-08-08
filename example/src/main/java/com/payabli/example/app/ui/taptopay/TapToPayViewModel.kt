package com.payabli.example.app.ui.taptopay

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.payabli.example.app.AppContainer
import com.payabli.example.app.config.DemoConfiguration
import com.payabli.example.app.config.TokenServerTarget
import com.payabli.example.app.net.TokenServerClient
import com.payabli.example.app.net.TokenServerProbe
import com.payabli.example.app.net.displayText
import com.payabli.example.app.preflight.DeviceFacts
import com.payabli.example.app.preflight.PreflightCheck
import com.payabli.example.app.preflight.Readiness
import com.payabli.example.app.preflight.TapToPayPreflight
import com.payabli.example.app.preflight.problemsIn
import com.payabli.example.app.preflight.readinessFrom
import com.payabli.example.app.terminal.EventBuffer
import com.payabli.example.app.terminal.TerminalAction
import com.payabli.example.app.terminal.TerminalActionOutcome
import com.payabli.example.app.terminal.TerminalController
import com.payabli.example.app.terminal.TerminalSessionState
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
    val isActivationOpen: Boolean = false,
    val isWorking: Boolean = false,
)

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
        _uiState.update { it.copy(tokenProbeText = "Checking…", isWorking = true) }
        viewModelScope.launch {
            val outcome = tokenClient.probeAccessToken()
            _uiState.update {
                it.copy(
                    tokenProbeText = outcome.displayText(TokenServerProbe.TOKEN_LABEL),
                    isWorking = false,
                )
            }
        }
    }

    /**
     * Runs one terminal action and turns its outcome into the single result line.
     *
     * No spinner anywhere on this screen. Progress is already visible in three places — the session
     * chip, the event list and the result line — and a fourth indicator over the top of them would
     * add nothing. `isWorking` only disables the buttons.
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
        _uiState.update { it.copy(isWorking = true) }
        viewModelScope.launch {
            val outcome = TerminalActionOutcome.from(action, block())
            _uiState.update { it.copy(resultText = outcome, isWorking = false) }
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
