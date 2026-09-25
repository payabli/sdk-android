package com.payabli.example.app.demo.simple

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.payabli.example.app.demo.payment.TransactionSummary
import com.payabli.example.app.demo.ui.components.DemoScreen
import com.payabli.example.app.demo.ui.customize.FormLookTheme
import com.payabli.example.app.demo.ui.customize.FormOperation
import com.payabli.example.app.demo.ui.customize.FormSettings
import com.payabli.example.app.demo.ui.customize.FormSettingsMenu
import com.payabli.example.app.sdk.FormCustomization
import com.payabli.example.app.sdk.PayInSessionSource
import com.payabli.sdk.payin.PayabliPayIn
import com.payabli.sdk.payin.PayabliPayInForm
import com.payabli.sdk.payin.model.PayInException
import com.payabli.sdk.payin.payment.PayInSubmissionState
import kotlinx.coroutines.launch
import java.math.BigDecimal

/**
 * The key the next attempt sends, from what this failure published and what is already held.
 *
 * **Whether a payment may still be outstanding is [PayInException.Unsettled]'s to say, and a key is the
 * narrower question of whether the SDK has one to offer.** They come apart on a refused repeat: a `409`
 * says the service has seen this key and nothing about whether the payment was taken, so it publishes no
 * key while the payment is still unresolved. Reading the published key as the answer drops the one held
 * here, and the next attempt mints a fresh one and starts a second payment for one that may already exist.
 *
 * Holding the refused key instead means the next attempt is refused too, which is the honest outcome: the
 * payer's next move is to read the transaction back rather than to pay again.
 *
 * **There is always a key to hold by the time a refusal arrives here.** A form submission names no payment,
 * so `RetryKey.reserve` never reuses a held key and sends either the one passed in or a fresh one; a `409`
 * means the service has seen the key before, which on this screen only a key it already held can be. So an
 * unresolved outcome that publishes no key cannot be the first thing this screen sees, and the branch that
 * would return null for one is unreachable rather than a gap left open.
 */
internal fun keyForNextAttempt(
    held: String?,
    outcome: PayInSubmissionState.Failed,
): String? = outcome.retryKey ?: held.takeIf { outcome.cause is PayInException.Unsettled }

/**
 * Holds the flow, so a rotation keeps the submission in flight and everything the payer has typed.
 *
 * `PayabliPayInForm` states that retention is the flow's owner's: held in the composition, the form empties
 * whenever it leaves it, and the key that makes a retry safe goes with it.
 *
 * A view model survives rotation and backgrounding, not the process ending, so [retryKey] is gone after a
 * kill and this screen cannot recover a payment interrupted that way. A host that needs to survive it sets
 * `idempotencyKey` on the transaction options itself and persists it before submitting;
 * `payin/src/androidTest/PROCESS-DEATH.md` covers what is and is not recoverable.
 */
class SimpleCaptureViewModel(
    sessionSource: PayInSessionSource,
    entryPoint: String,
) : ViewModel() {
    var payInFlow by mutableStateOf<PayabliPayIn?>(null)
        private set

    var failure by mutableStateOf<String?>(null)
        private set

    /**
     * The key the next attempt sends, so a retry after an unknown outcome settles the first charge instead
     * of making a second one.
     *
     * Null until a failure leaves the outcome unknown, and null again once one is settled: the SDK mints a
     * fresh key per attempt when none is given, which is what a new payment needs.
     */
    var retryKey by mutableStateOf<String?>(null)
        private set

    var settings by mutableStateOf(FormSettings())

    var operation by mutableStateOf(FormOperation.Capture)

    var amountText by mutableStateOf(DEFAULT_AMOUNT)

    fun failed(outcome: PayInSubmissionState.Failed) {
        retryKey = keyForNextAttempt(retryKey, outcome)
    }

    fun succeeded() {
        retryKey = null
    }

    init {
        // 1. The SDK is configured with a provider that calls the app's own backend. No token is minted
        //    here: the first request is what asks for one. The form waits on the session alone, which is
        //    why it is not drawn yet.
        viewModelScope.launch {
            sessionSource
                .session()
                .onSuccess {
                    // 2. One flow per screen, for this entry point.
                    payInFlow = PayabliPayIn(it, entryPoint, viewModelScope)
                }.onFailure { failure = it.message ?: "The session could not be configured." }
        }
    }

    private companion object {
        const val DEFAULT_AMOUNT = "12.34"
    }
}

/** A positive amount with at most two decimal places, or null. */
internal fun parseAmount(text: String): BigDecimal? =
    text
        .trim()
        .toBigDecimalOrNull()
        ?.takeIf { it.signum() > 0 && it.scale() <= 2 }

/**
 * Charging or storing a card with the fewest calls it takes: a token, a flow, a form.
 *
 * Every other screen here is wrapped in this app's own types so that four capabilities can share them. This
 * one calls the form directly, so a reader can see what the SDK asks for. The top bar's menu changes what the
 * form is configured with, which `sdk/FormCustomization.kt` spells out in the SDK's own types.
 */
@Composable
fun SimpleCaptureScreen(
    viewModel: SimpleCaptureViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val failure = viewModel.failure
    val payInFlow = viewModel.payInFlow
    val settings = viewModel.settings
    val operation = viewModel.operation
    val amount = parseAmount(viewModel.amountText)

    DemoScreen(
        title = "Simple Capture",
        modifier = modifier,
        actions = { FormSettingsMenu(settings) { viewModel.settings = it } },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FormOperation.entries.forEach { option ->
                FilterChip(
                    selected = option == operation,
                    onClick = { viewModel.operation = option },
                    label = { Text(option.label) },
                )
            }
        }
        if (operation == FormOperation.Capture) {
            OutlinedTextField(
                value = viewModel.amountText,
                onValueChange = { viewModel.amountText = it },
                label = { Text("Amount") },
                isError = amount == null,
                supportingText = { if (amount == null) Text("A positive amount, up to two decimals.") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        when {
            failure != null ->
                Text(
                    text = failure,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(24.dp),
                )

            payInFlow == null ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

            operation == FormOperation.Capture && amount == null -> Unit

            else ->
                FormLookTheme(settings.look) {
                    // 3. The form. It collects, validates and submits; the outcome arrives here.
                    PayabliPayInForm(
                        payIn = payInFlow,
                        operation =
                            FormCustomization.operation(
                                settings,
                                operation,
                                amount ?: BigDecimal.ZERO,
                                viewModel.retryKey,
                            ),
                        configuration =
                            FormCustomization.configuration(
                                settings,
                                operation,
                                amount?.let { TransactionSummary.formatAmount(it.toPlainString()) }.orEmpty(),
                            ),
                        labels = FormCustomization.labels(settings, operation),
                        style = FormCustomization.style(settings.look),
                        onCompleted = {
                            viewModel.succeeded()
                            val done = if (operation == FormOperation.Capture) "Payment approved" else "Card saved"
                            Toast.makeText(context, done, Toast.LENGTH_LONG).show()
                        },
                        onFailed = {
                            viewModel.failed(it)
                            val failed = if (operation == FormOperation.Capture) "Payment failed" else "Save failed"
                            Toast.makeText(context, failed, Toast.LENGTH_LONG).show()
                        },
                        onMethodChanged = {},
                    )
                }
        }
    }
}
