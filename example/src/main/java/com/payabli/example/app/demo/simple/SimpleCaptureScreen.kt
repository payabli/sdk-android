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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.payabli.example.app.sdk.PayInSessionSource
import com.payabli.sdk.payin.PayabliPayInForm
import com.payabli.sdk.payin.form.PayInFormConfiguration
import com.payabli.sdk.payin.model.PayInPaymentDetails
import com.payabli.sdk.payin.model.PayInTransactionOptions
import com.payabli.sdk.payin.payment.PayabliPayInOperation
import com.payabli.sdk.payin.payment.PayabliPayInPaymentFlow
import java.math.BigDecimal

/**
 * Charging a card with the fewest calls it takes: a token, a flow, a form.
 *
 * Every other screen here is wrapped in this app's own types so that four capabilities can share them. This
 * one is not, so a reader can see what the SDK asks for and what belongs to the sample. Three calls, in
 * order, and nothing else on the screen.
 *
 * **The flow is held in the composition, which a real integration should not do.** It holds what the payer
 * has typed, so a rotation empties this form and loses a submission in flight. Hold it where the screen's
 * state lives, which is a ViewModel and `viewModelScope`. It is here because the point of this screen is to
 * be one file.
 */
@Composable
fun SimpleCaptureScreen(
    sessionSource: PayInSessionSource,
    entryPoint: String,
    amount: BigDecimal,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var flow by remember { mutableStateOf<PayabliPayInPaymentFlow?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }

    // 1. The app's own backend mints a token and the SDK is configured with it. Nothing can be sent until
    //    this has answered, which is why the form is not drawn yet.
    LaunchedEffect(entryPoint) {
        sessionSource
            .session()
            .onSuccess {
                // 2. One flow per screen, for this entry point.
                flow = PayabliPayInPaymentFlow(it, entryPoint, scope)
            }.onFailure { failure = it.message ?: "The session could not be configured." }
    }

    val ready = flow
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
                                PayInTransactionOptions(PayInPaymentDetails(totalAmount = amount)),
                            ),
                        configuration = PayInFormConfiguration(),
                        onCompleted = {
                            Toast.makeText(context, "Payment approved", Toast.LENGTH_LONG).show()
                        },
                        onFailed = {
                            Toast.makeText(context, "Payment failed", Toast.LENGTH_LONG).show()
                        },
                        onMethodChanged = {},
                    )
                }
        }
    }
}
