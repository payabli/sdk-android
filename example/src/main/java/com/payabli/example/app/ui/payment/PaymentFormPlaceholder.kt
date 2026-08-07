package com.payabli.example.app.ui.payment

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.payabli.example.app.payment.PaymentError
import com.payabli.example.app.payment.PaymentFormConfiguration
import com.payabli.example.app.payment.PaymentResult
import com.payabli.example.app.payment.StoredMethod
import com.payabli.example.app.ui.components.BorderedButton
import com.payabli.example.app.ui.components.DemoIcons
import com.payabli.example.app.ui.components.PreviewSurface
import com.payabli.example.app.ui.components.ProminentButton
import com.payabli.example.app.ui.components.SectionHeader
import com.payabli.example.app.ui.theme.Dimens

/**
 * Where the SDK's payment form will mount.
 *
 * It renders no fields, on purpose. Collecting a card number, masking a security code and deciding
 * whether an expiry is in the past all belong to the SDK component, and a sample app that did them
 * would be putting instrument capture in a file with none of the protections that component has,
 * while inviting anyone reading it to copy the pattern.
 *
 * What the app does own is around this box: the call site, the configuration handed to it, the result
 * and error models, the sheet chrome and the outcome screens. Those are what the component plugs
 * into, and none of them changes when it arrives.
 *
 * Both buttons stay so no downstream screen is unreachable while there is no form: one produces a
 * result, the other an error.
 */
@Composable
fun PaymentFormPlaceholder(
    configuration: PaymentFormConfiguration,
    onCompleted: (PaymentResult) -> Unit,
    onError: (PaymentError) -> Unit,
    modifier: Modifier = Modifier,
    isSubmitting: Boolean = false,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
    ) {
        SectionHeader(title = configuration.title, note = configuration.subtitle)

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceContainer,
                        RoundedCornerShape(Dimens.CardCorner),
                    ).padding(vertical = 40.dp, horizontal = Dimens.CardPadding),
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "Payment form",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "The SDK renders this. What it collects is listed on the Setup screen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        ProminentButton(
            text = if (isSubmitting) "Submitting…" else configuration.submitLabel,
            icon = DemoIcons.Pass,
            onClick = { onCompleted(demoResult()) },
            enabled = !isSubmitting,
        )
        BorderedButton(
            text = "Simulate a failure",
            icon = DemoIcons.Fail,
            onClick = {
                onError(PaymentError.Payabli("Declined", "The card was declined by the issuer."))
            },
            enabled = !isSubmitting,
            contentColor = MaterialTheme.colorScheme.error,
        )
    }
}

/** A result of the right shape, so the outcome screens render real content today. */
private fun demoResult(): PaymentResult =
    PaymentResult(
        code = "1",
        reason = "Success",
        storedMethod = StoredMethod("demo-method-0001", "Payment method saved", "Approved"),
    )

@PreviewLightDark
@Composable
private fun PaymentFormPlaceholderPreview() {
    PreviewSurface {
        PaymentFormPlaceholder(
            configuration = PaymentFormConfiguration.capture(),
            onCompleted = {},
            onError = {},
        )
    }
}
