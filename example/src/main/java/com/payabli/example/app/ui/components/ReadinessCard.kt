package com.payabli.example.app.ui.components

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.platform.LocalContext
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

/**
 * The compact panel where the platform has one, and the full settings screen otherwise.
 *
 * The panel slides over this screen instead of replacing it, so the toggle and the step that needed
 * it stay in the same place.
 */
private fun panelOrScreenFor(action: String): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && action == Settings.ACTION_NFC_SETTINGS) {
        Settings.Panel.ACTION_NFC
    } else {
        action
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
            // Only the checks that name a settings screen. No app can switch NFC on: the platform
            // API is a system one, and even the adb shell uid is refused, so the most this can offer
            // is the fastest route to the switch. The verdict is read again on the way back.
            check.settingsAction?.let { action ->
                val context = LocalContext.current
                TextButton(
                    onClick = { context.startActivity(Intent(panelOrScreenFor(action))) },
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Text("Turn it on")
                }
            }
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
        // The resting tone. "Could not check" is not a problem, and amber reads as one.
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
