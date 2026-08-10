package com.payabli.example.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewLightDark
import com.payabli.example.app.ui.theme.Dimens

/**
 * The control and the answer for the token-backend step, shared by both payment screens.
 *
 * One place, so the two cannot word the same check differently.
 */
@Composable
fun TokenCheckStep(
    text: String,
    isChecking: Boolean,
    onCheck: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing)) {
        BorderedButton(
            text = if (isChecking) "Checking…" else "Check token endpoint",
            icon = DemoIcons.CheckToken,
            onClick = onCheck,
            enabled = !isChecking,
        )
        if (text.isNotEmpty()) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                // The probe marks its own failures, and this reads that mark rather than deciding
                // again. Two places deciding what failed is how they come to disagree.
                color =
                    if (text.startsWith("✗")) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
        }
    }
}

@PreviewLightDark
@Composable
private fun TokenCheckStepPreview() {
    PreviewSurface {
        TokenCheckStep(text = "", isChecking = false, onCheck = {})
        TokenCheckStep(text = "Checking…", isChecking = true, onCheck = {})
        TokenCheckStep(text = "✓ access token returned a token", isChecking = false, onCheck = {})
        TokenCheckStep(text = "✗ access token unreachable: port 8787", isChecking = false, onCheck = {})
    }
}
