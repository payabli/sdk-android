package com.payabli.example.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewLightDark
import com.payabli.example.app.ui.theme.Dimens

/**
 * Redacted request and response logging, when it is switched on.
 *
 * Renders nothing at all when [isEnabled] is false. A heading over an empty list suggests the log is
 * empty, when in fact nothing is being collected.
 */
@Composable
fun DiagnosticsPanel(
    messages: List<String>,
    isEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!isEnabled) return

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
    ) {
        SectionHeader(title = "Diagnostics")
        if (messages.isEmpty()) {
            Text(
                text = "No requests yet",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            messages.forEach { message ->
                SelectableMonospaceBlock(text = message)
            }
        }
    }
}

@PreviewLightDark
@Composable
private fun DiagnosticsPanelPreview() {
    PreviewSurface {
        DiagnosticsPanel(messages = emptyList(), isEnabled = true)
        DiagnosticsPanel(
            isEnabled = true,
            messages =
                listOf(
                    "REQUEST POST /api/paymentMethod\nheaders={requestToken=<redacted>}\nbody=<redacted>",
                    "RESPONSE 200 /api/paymentMethod\ndurationMillis=412",
                ),
        )
        // isEnabled = false renders nothing at all, which is why there is no third block here.
        DiagnosticsPanel(messages = listOf("never shown"), isEnabled = false)
    }
}
