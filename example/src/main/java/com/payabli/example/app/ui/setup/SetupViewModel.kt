package com.payabli.example.app.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.payabli.example.app.AppContainer
import com.payabli.example.app.config.DemoConfiguration
import com.payabli.example.app.config.TokenServerTarget
import com.payabli.example.app.net.TokenServerClient
import com.payabli.example.app.net.TokenServerProbe
import com.payabli.example.app.net.displayText
import com.payabli.example.app.payment.PaymentFormConfiguration
import com.payabli.example.app.preflight.DeviceFacts
import com.payabli.example.app.preflight.PreflightCheck
import com.payabli.example.app.preflight.Readiness
import com.payabli.example.app.preflight.TapToPayPreflight
import com.payabli.example.app.preflight.problemsIn
import com.payabli.example.app.preflight.readinessFrom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SetupUiState(
    val configuration: DemoConfiguration,
    val tokenServer: TokenServerTarget,
    val deviceFacts: DeviceFacts,
    /** The very object the payment screen hands its form, so this screen cannot describe another one. */
    val formConfiguration: PaymentFormConfiguration,
    val readiness: Readiness = Readiness.Ready,
    val problems: List<PreflightCheck> = emptyList(),
    val tokenProbeText: String = "",
    val healthProbeText: String = "",
    val isProbing: Boolean = false,
)

class SetupViewModel(
    private val configuration: DemoConfiguration,
    private val tokenServer: TokenServerTarget,
    private val tokenClient: TokenServerClient,
    private val readDeviceFacts: () -> DeviceFacts,
    formConfiguration: PaymentFormConfiguration,
) : ViewModel() {
    private val _uiState =
        MutableStateFlow(
            SetupUiState(
                configuration = configuration,
                tokenServer = tokenServer,
                deviceFacts = readDeviceFacts(),
                formConfiguration = formConfiguration,
            ),
        )
    val uiState: StateFlow<SetupUiState> = _uiState.asStateFlow()

    init {
        recheck()
    }

    fun recheck() {
        val facts = readDeviceFacts()
        val checks = TapToPayPreflight.checks(facts, configuration.appId, configuration.signingCertificate)
        _uiState.update {
            it.copy(deviceFacts = facts, readiness = readinessFrom(checks), problems = problemsIn(checks))
        }
    }

    fun probeToken() {
        probe(TokenServerProbe.TOKEN_LABEL) { text ->
            _uiState.update { it.copy(tokenProbeText = text) }
        }
    }

    fun probeHealth() {
        probe(TokenServerProbe.HEALTH_LABEL) { text ->
            _uiState.update { it.copy(healthProbeText = text) }
        }
    }

    private fun probe(
        label: String,
        publish: (String) -> Unit,
    ) {
        // Single flight, as on the other screens. `isProbing` disables both buttons, but only once
        // the state has recomposed, and this screen has two callers into it.
        if (_uiState.value.isProbing) return
        publish("Checking…")
        _uiState.update { it.copy(isProbing = true) }
        viewModelScope.launch {
            val outcome =
                if (label == TokenServerProbe.TOKEN_LABEL) {
                    tokenClient.probeAccessToken()
                } else {
                    tokenClient.probeHealth()
                }
            publish(outcome.displayText(label))
            _uiState.update { it.copy(isProbing = false) }
        }
    }

    companion object {
        fun from(container: AppContainer): SetupViewModel =
            SetupViewModel(
                configuration = container.configuration,
                tokenServer = container.tokenServer,
                tokenClient = container.tokenClient,
                readDeviceFacts = container.readDeviceFacts,
                // The payment-method controller's own configuration, not a fresh copy. The two
                // operations differ only in their summary section, so either describes the form.
                formConfiguration = container.paymentMethodFlow.configuration,
            )
    }
}
