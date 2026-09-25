package com.payabli.example.app.demo.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.payabli.example.app.demo.ui.theme.Dimens

/** Who draws what is inside a frame: this app, or the SDK. */
enum class Owner(
    val label: String,
    val note: String,
) {
    App("Your app", "Written by the integrator."),
    Sdk("Payabli SDK", "Drawn by PayabliPayInForm."),
}

/**
 * A labelled frame naming who owns its content, so a screen shows where the app ends and the SDK begins.
 *
 * The app's frame is solid and the SDK's is dashed, so the two read apart in a screenshot without colour.
 */
@Composable
fun OwnerFrame(
    owner: Owner,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val color = if (owner == Owner.Sdk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .then(if (owner == Owner.Sdk) Modifier.dashedBorder(color) else Modifier.border(1.dp, color, shape))
                .padding(Dimens.CardPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
    ) {
        Text(
            text = "${owner.label} · ${owner.note}",
            style = MaterialTheme.typography.labelMedium,
            color = color,
            modifier = Modifier.semantics { heading() },
        )
        content()
    }
}

private fun Modifier.dashedBorder(color: Color): Modifier =
    drawBehind {
        drawRoundRect(
            color = color,
            cornerRadius = CornerRadius(12.dp.toPx()),
            style =
                Stroke(
                    width = 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 6.dp.toPx())),
                ),
        )
    }
