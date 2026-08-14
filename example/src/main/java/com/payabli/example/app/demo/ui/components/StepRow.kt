package com.payabli.example.app.demo.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.PreviewLightDark
import com.payabli.example.app.demo.flow.FlowStep
import com.payabli.example.app.demo.flow.StepStatus
import com.payabli.example.app.demo.ui.theme.Dimens
import com.payabli.example.app.demo.ui.theme.LocalStatusColors

/**
 * One step of a flow, numbered. [StepStatus] decides whether it shows
 * its controls.
 */
@Composable
fun StepRow(
    index: Int,
    step: FlowStep,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit = {},
) {
    val tint = step.status.tint()
    Card(
        modifier = modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                // The step being acted on sits forward of the rest.
                containerColor =
                    if (step.status == StepStatus.Current) {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    } else {
                        MaterialTheme.colorScheme.surfaceContainer
                    },
            ),
    ) {
        Column(
            modifier = Modifier.padding(Dimens.ItemSpacing),
            verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(imageVector = step.status.icon(), contentDescription = null, tint = tint)

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(Dimens.StepTitleGap),
                ) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            text = "$index. ${step.title}",
                            style = MaterialTheme.typography.titleSmall,
                            color = step.status.titleColour(),
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Text(
                            text = step.status.label(),
                            style = MaterialTheme.typography.labelMedium,
                            color = tint,
                        )
                    }
                    Text(
                        text = step.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (step.status.showsContent) content()
        }
    }
}

@Composable
private fun StepStatus.label(): String =
    when (this) {
        StepStatus.Done -> "done"
        StepStatus.Current -> "do this next"
        StepStatus.InProgress -> "working…"
        StepStatus.Blocked -> "waiting"
        StepStatus.NotNeeded -> "not needed"
        StepStatus.Failed -> "failed"
    }

@Composable
private fun StepStatus.icon(): ImageVector =
    when (this) {
        StepStatus.Done -> DemoIcons.Pass
        StepStatus.Current -> DemoIcons.Current
        StepStatus.InProgress -> DemoIcons.Working
        StepStatus.Blocked -> DemoIcons.Waiting
        StepStatus.NotNeeded -> DemoIcons.NotNeeded
        StepStatus.Failed -> DemoIcons.Fail
    }

/** The same tones the readiness card and the session chip use, so one vocabulary covers the app. */
@Composable
private fun StepStatus.tint(): Color =
    when (this) {
        StepStatus.Done -> LocalStatusColors.current.success
        StepStatus.Current, StepStatus.InProgress -> MaterialTheme.colorScheme.primary
        StepStatus.Blocked, StepStatus.NotNeeded -> LocalStatusColors.current.neutral
        StepStatus.Failed -> MaterialTheme.colorScheme.error
    }

/** A step nobody can act on yet recedes, so the eye lands on the one that is asking. */
@Composable
private fun StepStatus.titleColour(): Color =
    when (this) {
        StepStatus.Blocked, StepStatus.NotNeeded -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurface
    }

@PreviewLightDark
@Composable
private fun StepRowPreview() {
    PreviewSurface {
        // Every status, because the whole job of this row is to look different in each and the only
        // way to see that is side by side.
        StepStatus.entries.forEachIndexed { index, status ->
            StepRow(
                index = index + 1,
                step =
                    FlowStep(
                        title = "Reach the token backend",
                        detail =
                            "Your backend mints the token. This step only checks that it " +
                                "answers; the SDK is not involved.",
                        status = status,
                    ),
            ) {
                BorderedButton(text = "Check token endpoint", icon = DemoIcons.CheckToken, onClick = {})
            }
        }
    }
}
