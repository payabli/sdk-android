package com.payabli.example.app.demo.ui.components

import android.provider.Settings
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Fades and lifts its content into place after a delay.
 *
 * Used to stagger the parts of an outcome screen behind the mark that heads it, so a reader's eye
 * lands on the result before the sentence explaining it.
 *
 * Nothing is withheld while it waits. The content is composed and laid out from the first frame and
 * only how it is drawn changes, so it is in the semantics tree the whole time and a screen reader
 * reaches every part of the screen from the moment it opens. `AnimatedVisibility` does not compose
 * its content until it becomes visible, which would leave the parts of an outcome unreachable for as
 * long as the stagger lasts.
 *
 * With animations turned off the finished screen is what is drawn on the first frame.
 */
@Composable
fun EntersAfter(
    delayMillis: Int,
    content: @Composable () -> Unit,
) {
    val animate = animationsEnabled()
    var shown by remember { mutableStateOf(!animate) }

    LaunchedEffect(animate) {
        if (animate) {
            delay(delayMillis.toLong())
            shown = true
        }
    }

    val entered by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(ENTER_MILLIS, easing = FastOutSlowInEasing),
        label = "entersAfter",
    )

    Box(
        modifier =
            Modifier.graphicsLayer {
                alpha = entered
                translationY = (1f - entered) * LIFT.toPx()
            },
    ) {
        content()
    }
}

/** The animator duration scale, which is 0 when someone has asked for less motion. */
@Composable
private fun animationsEnabled(): Boolean {
    if (LocalInspectionMode.current) return false
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }
}

private const val ENTER_MILLIS = 260

/** How far the content lifts as it arrives. */
private val LIFT = 12.dp
