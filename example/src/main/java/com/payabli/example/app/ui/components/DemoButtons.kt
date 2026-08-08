package com.payabli.example.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.payabli.example.app.ui.theme.LocalStatusColors

/**
 * The two button weights the app uses, and no others.
 *
 * The icon is required. Every action here has one, and a row of buttons where some have icons and
 * some do not reads as an oversight.
 */
@Composable
fun ProminentButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
    ) {
        ButtonContent(text, icon)
    }
}

@Composable
fun BorderedButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentColor: Color = MaterialTheme.colorScheme.primary,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = contentColor),
        modifier = modifier.fillMaxWidth(),
    ) {
        ButtonContent(text, icon)
    }
}

@Composable
private fun ButtonContent(
    text: String,
    icon: ImageVector,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            // Null: the label beside it already says what this does, and a screen reader announcing
            // both would read the action twice.
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Text(text)
    }
}

@PreviewLightDark
@Composable
private fun DemoButtonsPreview() {
    PreviewSurface {
        ProminentButton(text = "Open bottom sheet", icon = DemoIcons.OpenSheet, onClick = {})
        // Disabled next to enabled, because the disabled state is what most of these spend their
        // time in and it is the one nobody looks at.
        ProminentButton(text = "Charge", icon = DemoIcons.Charge, onClick = {}, enabled = false)
        BorderedButton(text = "Check token", icon = DemoIcons.CheckToken, onClick = {})
        BorderedButton(text = "Health", icon = DemoIcons.CheckHealth, onClick = {}, enabled = false)
        BorderedButton(
            text = "Fill in test data",
            icon = DemoIcons.Prefill,
            onClick = {},
            contentColor = LocalStatusColors.current.warning,
        )
    }
}
