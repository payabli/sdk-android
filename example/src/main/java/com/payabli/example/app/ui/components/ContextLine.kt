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
 * The entry point and the host only. The full set is on the Setup screen.
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
            // A blank value is left out. "· host" reads as a rendering fault, and a fresh clone
            // has no entry point.
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

/** One host, so the rows differ only in the entry point. */
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
