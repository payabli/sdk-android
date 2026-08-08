package com.payabli.example.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.payabli.example.app.preflight.CheckStatus
import com.payabli.example.app.preflight.PreflightCheck
import com.payabli.example.app.preflight.Readiness
import com.payabli.example.app.ui.theme.Dimens
import com.payabli.example.app.ui.theme.LocalStatusColors

/**
 * The verdict, then only the checks that are not simply fine.
 *
 * Problems only. Listing five green ticks buries the one amber row among them, and a reader who has
 * to scan a list to find the problem is being asked to do the card's job. When everything passes
 * there is one line saying so.
 *
 * Both the Tap to pay and Setup screens use this, so the two cannot reach different conclusions from
 * the same facts. The verdict itself is computed in `readinessFrom`, not here.
 */
@Composable
fun ReadinessCard(
    readiness: Readiness,
    problems: List<PreflightCheck>,
    onRecheck: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val statusColors = LocalStatusColors.current
    val verdictColor =
        when (readiness) {
            Readiness.Ready -> statusColors.success
            Readiness.ActionNeeded -> statusColors.warning
            Readiness.NotAvailable -> MaterialTheme.colorScheme.error
        }
    val verdictIcon =
        when (readiness) {
            Readiness.Ready -> DemoIcons.Pass
            Readiness.ActionNeeded -> DemoIcons.Warn
            Readiness.NotAvailable -> DemoIcons.NotAvailable
        }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = verdictIcon,
                contentDescription = null,
                tint = verdictColor,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.size(Dimens.ItemSpacing))
            Text(
                text = readiness.title,
                style = MaterialTheme.typography.titleMedium,
                color = verdictColor,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onRecheck) {
                Text("Check again")
            }
        }

        if (problems.isEmpty()) {
            Text(
                text = "Every check passed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            problems.forEach { check ->
                CheckRow(check)
            }
        }
    }
}

@Composable
private fun CheckRow(check: PreflightCheck) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(Dimens.CardCorner))
                .padding(Dimens.RowPadding),
        horizontalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
    ) {
        Icon(
            imageVector = check.status.icon(),
            contentDescription = null,
            tint = check.status.tint(),
            modifier = Modifier.size(18.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = check.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = check.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CheckStatus.icon(): ImageVector =
    when (this) {
        CheckStatus.Pass -> DemoIcons.Pass
        CheckStatus.Warn -> DemoIcons.Warn
        CheckStatus.Fail -> DemoIcons.Fail
        CheckStatus.Unknown -> DemoIcons.Unknown
    }

@Composable
private fun CheckStatus.tint(): Color =
    when (this) {
        CheckStatus.Pass -> LocalStatusColors.current.success
        CheckStatus.Warn -> LocalStatusColors.current.warning
        CheckStatus.Fail -> MaterialTheme.colorScheme.error
        // Deliberately the resting tone, not amber: "could not check" must not read as "problem".
        CheckStatus.Unknown -> LocalStatusColors.current.neutral
    }

@PreviewLightDark
@Composable
private fun ReadinessCardPreview() {
    PreviewSurface {
        ReadinessCard(readiness = Readiness.Ready, problems = emptyList(), onRecheck = {})
        ReadinessCard(
            readiness = Readiness.ActionNeeded,
            problems =
                listOf(
                    PreflightCheck(
                        "NFC switched off",
                        "The hardware is present. Turn NFC on in Settings before taking a payment.",
                        CheckStatus.Warn,
                    ),
                ),
            onRecheck = {},
        )
        ReadinessCard(
            readiness = Readiness.NotAvailable,
            problems =
                listOf(
                    PreflightCheck(
                        "Emulator",
                        "Google Pixel 8. A contactless payment needs real hardware and cannot be taken here.",
                        CheckStatus.Fail,
                    ),
                    PreflightCheck(
                        "Signing certificate unreadable",
                        "The app ID matches. Reading the certificate needs API 28 or newer, so it was not checked.",
                        CheckStatus.Unknown,
                    ),
                ),
            onRecheck = {},
        )
    }
}
