package com.payabli.example.app.demo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.payabli.example.app.demo.ui.theme.Dimens
import com.payabli.example.app.demo.ui.theme.LocalStatusColors
import com.payabli.example.app.demo.ui.theme.monospaceBodyMedium

/**
 * One configured value, shown and not edited.
 *
 * Text and never a text field. Every value this displays was captured when the app started and
 * nothing at runtime can change it, so an editable field would promise something the app cannot
 * honour.
 *
 * @param problem when set, an amber line under the value saying what is wrong with it. This is how a
 *   blank entry point or a mismatched app id becomes visible on the screen that reads it, before it
 *   can surface much later as a rejected request.
 */
@Composable
fun DetailRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    problem: String? = null,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(Dimens.CardCorner))
                .padding(Dimens.RowPadding),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val shown = value.ifEmpty { "—" }
        // A long value goes under its label. Sharing one line, it wraps at whatever column is left
        // over and leaves a short ragged first line. The threshold is where that starts at this text
        // size on a phone.
        if (shown.length <= INLINE_VALUE_LIMIT) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f).width(Dimens.LabelValueGap))
                SelectionContainer {
                    Text(
                        text = shown,
                        style = monospaceBodyMedium(),
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.End,
                    )
                }
            }
        } else {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SelectionContainer {
                Text(
                    text = shown,
                    style = monospaceBodyMedium(),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (problem != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing / 2),
            ) {
                Icon(
                    imageVector = DemoIcons.Warn,
                    contentDescription = null,
                    tint = LocalStatusColors.current.warning,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = problem,
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalStatusColors.current.warning,
                )
            }
        }
    }
}

/** Past this many characters a value gets its own line. */
private const val INLINE_VALUE_LIMIT = 22

@PreviewLightDark
@Composable
private fun DetailRowPreview() {
    PreviewSurface {
        // Short, long and empty together, because the three take different paths through the layout.
        DetailRow(label = "Method", value = "card")
        DetailRow(label = "Environment", value = "sandbox · api-sandbox.payabli.com")
        DetailRow(label = "Chosen because", value = "emulator, 10.0.2.2 reaches this machine's loopback")
        DetailRow(label = "App ID", value = "com.payabli.example.app")
        // The empty value and the problem line together, because that pairing is the one worth
        // looking at: a dash where a value should be, and the reason underneath it.
        DetailRow(label = "Entry point", value = "", problem = "Not set. Configuration is keyed by entry point.")
        DetailRow(label = "Payment trans ID", value = "txn_01JQ8F3M2K7WZ9XB4C6D8E0GHR")
    }
}
