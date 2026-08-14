package com.payabli.example.app.demo.ui.components

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.payabli.example.app.demo.ui.theme.Dimens
import com.payabli.example.app.demo.ui.theme.monospaceLabelSmall

/**
 * One entry in the terminal's event stream.
 *
 * Monospace on both lines: these are wire-level codes, and a reader comparing two of them is
 * comparing characters.
 */
@Composable
fun EventRow(
    label: String,
    detail: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(Dimens.CardCorner))
                .padding(Dimens.RowPadding),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            style = monospaceLabelSmall().copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (detail.isNotEmpty()) {
            Text(
                text = detail,
                style = monospaceLabelSmall(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@PreviewLightDark
@Composable
private fun EventRowPreview() {
    PreviewSurface {
        EventRow(label = "readerReady", detail = "")
        EventRow(label = "configReceived", detail = "entryPoint=test6 environment=sandbox")
        EventRow(label = "nfcFailed", detail = "reason=cardRemovedTooSoon retryable=true")
    }
}
