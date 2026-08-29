package com.payabli.example.app.demo.simple

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.payabli.example.app.demo.payment.TransactionSummary
import com.payabli.example.app.sdk.PayInSessionSource
import com.payabli.sdk.payin.PayabliPayInForm
import com.payabli.sdk.payin.form.PayInField
import com.payabli.sdk.payin.form.PayInFormConfiguration
import com.payabli.sdk.payin.form.PayInFormSection
import com.payabli.sdk.payin.form.PayInMethodType
import com.payabli.sdk.payin.form.PayInSectionStyle
import com.payabli.sdk.payin.model.PayInPaymentDetails
import com.payabli.sdk.payin.model.PayInTransactionOptions
import com.payabli.sdk.payin.payment.PayInSubmissionState
import com.payabli.sdk.payin.payment.PayabliPayInOperation
import com.payabli.sdk.payin.payment.PayabliPayInPaymentFlow
import kotlinx.coroutines.launch
import java.math.BigDecimal

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
    var flow by mutableStateOf<PayabliPayInPaymentFlow?>(null)
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

    fun failed(outcome: PayInSubmissionState.Failed) {
        retryKey = outcome.retryKey
    }

    fun succeeded() {
        retryKey = null
    }

    init {
        // 1. The app's own backend mints a token and the SDK is configured with it. Nothing can be sent
        //    until this has answered, which is why the form is not drawn yet.
        viewModelScope.launch {
            sessionSource
                .session()
                .onSuccess {
                    // 2. One flow per screen, for this entry point.
                    flow = PayabliPayInPaymentFlow(it, entryPoint, viewModelScope)
                }.onFailure { failure = it.message ?: "The session could not be configured." }
        }
    }
}

/**
 * Charging a card with the fewest calls it takes: a token, a flow, a form.
 *
 * Every other screen here is wrapped in this app's own types so that four capabilities can share them. This
 * one is not, so a reader can see what the SDK asks for and what belongs to the sample. Three calls, in
 * order, and nothing else on the screen.
 */
@Composable
fun SimpleCaptureScreen(
    viewModel: SimpleCaptureViewModel,
    amount: BigDecimal,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val failure = viewModel.failure

    val ready = viewModel.flow
    Box(modifier = modifier.fillMaxSize()) {
        when {
            failure != null ->
                Text(
                    text = failure.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )

            ready == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))

            else ->
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
                ) {
                    // 3. The form. It collects, validates and submits; the outcome arrives here.
                    PayabliPayInForm(
                        flow = ready,
                        operation =
                            PayabliPayInOperation.Capture(
                                PayInTransactionOptions(
                                    PayInPaymentDetails(totalAmount = amount),
                                    idempotencyKey = viewModel.retryKey,
                                ),
                            ),
                        configuration =
                            PayInFormConfiguration(
                                allowedMethods = listOf(PayInMethodType.Card),
                                // The amount is set on the operation and never collected, so this row reads
                                // back the figure the request carries and the two cannot disagree.
                                cardSections =
                                    PayInFormConfiguration.defaultCardSections() +
                                        // A capture with no customer is refused with 400 "Error in customer
                                        // data", so the three the service needs are collected here.
                                        PayInFormSection(
                                            fields =
                                                listOf(
                                                    PayInField.FirstName,
                                                    PayInField.LastName,
                                                    PayInField.BillingEmail,
                                                ),
                                        ) +
                                        PayInFormSection(
                                            fields = listOf(PayInField.Amount),
                                            style = PayInSectionStyle.Summary,
                                        ),
                                summaryValues =
                                    mapOf(
                                        PayInField.Amount to
                                            TransactionSummary.formatAmount(amount.toPlainString()),
                                    ),
                            ),
                        onCompleted = {
                            viewModel.succeeded()
                            Toast.makeText(context, "Payment approved", Toast.LENGTH_LONG).show()
                        },
                        onFailed = {
                            viewModel.failed(it)
                            Toast.makeText(context, "Payment failed", Toast.LENGTH_LONG).show()
                        },
                        onMethodChanged = {},
                    )
                }
        }
    }
}
