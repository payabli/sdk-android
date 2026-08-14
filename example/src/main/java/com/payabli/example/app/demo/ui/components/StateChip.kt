package com.payabli.example.app.demo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.payabli.example.app.demo.terminal.ChipTone
import com.payabli.example.app.demo.terminal.SessionChipSpec
import com.payabli.example.app.demo.terminal.TerminalSessionState
import com.payabli.example.app.demo.terminal.chipSpecFor
import com.payabli.example.app.demo.ui.theme.LocalStatusColors

/**
 * The session state, in the top app bar.
 *
 * A coloured dot sits beside the label, so the state is legible to a reader who cannot distinguish
 * the tones and in a monochrome screenshot.
 *
 * `chipSpecFor` decides the label and tone. That keeps the mapping unit-testable and leaves this a
 * renderer.
 */
@Composable
fun StateChip(
    spec: SessionChipSpec,
    modifier: Modifier = Modifier,
) {
    val tint = spec.tone.tint()
    Row(
        modifier =
            modifier
                .background(tint.copy(alpha = 0.16f), CircleShape)
                .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        androidx.compose.foundation.layout.Box(
            Modifier.size(8.dp).background(tint, CircleShape),
        )
        Text(
            text = spec.label,
            style = MaterialTheme.typography.labelMedium,
            color = tint,
        )
    }
}

@Composable
private fun ChipTone.tint(): Color =
    when (this) {
        ChipTone.Ready -> LocalStatusColors.current.success
        ChipTone.Alert -> MaterialTheme.colorScheme.error
        ChipTone.Pending -> LocalStatusColors.current.warning
        ChipTone.Neutral -> LocalStatusColors.current.neutral
    }

@PreviewLightDark
@Composable
private fun StateChipPreview() {
    PreviewSurface {
        // Every state, because this is the one component whose whole job is to look different in
        // each of them, and the only way to see that is side by side.
        TerminalSessionState.entries.forEach { state ->
            StateChip(spec = chipSpecFor(state))
        }
    }
}
