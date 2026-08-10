package com.payabli.example.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewLightDark
import com.payabli.example.app.ui.theme.Dimens

/**
 * What this screen is pointed at, in one line.
 *
 * Every value is on the Setup screen in full. Repeating the set on each payment screen is what turns
 * one into a wall of rows nobody reads, and the entry point and the host are the two that decide
 * whether a request goes anywhere.
 */
@Composable
fun ContextLine(
    entryPoint: String,
    host: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = DemoIcons.Setup,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            // A blank value is left out rather than separated from nothing. An unconfigured clone
            // has no entry point, and "· host" reads as a rendering fault.
            text = listOf(entryPoint, host).filter { it.isNotBlank() }.joinToString(" · ").ifEmpty { "not configured" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "details in Setup",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One host across the preview, so the rows differ only in the thing each is showing. */
private const val PREVIEW_HOST = "api-sandbox.payabli.com"

@PreviewLightDark
@Composable
private fun ContextLinePreview() {
    PreviewSurface {
        ContextLine(entryPoint = "my-entry-point", host = PREVIEW_HOST)
        // A long entry point ellipsises rather than pushing the tail off the row.
        ContextLine(entryPoint = "an-unusually-long-entry-point-name", host = PREVIEW_HOST)
        // What a fresh clone shows before its entry point is set.
        ContextLine(entryPoint = "", host = PREVIEW_HOST)
        ContextLine(entryPoint = "", host = "")
    }
}
