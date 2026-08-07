package com.payabli.example.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.payabli.example.app.ui.theme.Dimens

/** How much weight a heading carries. */
enum class SectionEmphasis {
    /** A block within a screen. */
    Section,

    /** A top-level division of a screen that has several. */
    Screen,
}

/**
 * A heading, and optionally the one line of explanation that stops the block below it being a
 * mystery.
 *
 * @param note kept to a sentence. Anything longer belongs in the README, not on the screen.
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    emphasis: SectionEmphasis = SectionEmphasis.Section,
    note: String? = null,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = title,
            style =
                when (emphasis) {
                    SectionEmphasis.Section -> MaterialTheme.typography.titleMedium
                    SectionEmphasis.Screen -> MaterialTheme.typography.titleLarge
                },
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (note != null) {
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The gap a caller should leave between a header and the content it introduces. */
val SectionHeaderSpacing = Dimens.ItemSpacing

@PreviewLightDark
@Composable
private fun SectionHeaderPreview() {
    PreviewSurface {
        SectionHeader(
            title = "Setup",
            emphasis = SectionEmphasis.Screen,
            note = "What the SDK was configured with. Captured at startup, so none of it is editable here.",
        )
        SectionHeader(title = "Token endpoint")
        SectionHeader(
            title = "Card present",
            note = "Whether this device can take a contactless payment.",
        )
    }
}
