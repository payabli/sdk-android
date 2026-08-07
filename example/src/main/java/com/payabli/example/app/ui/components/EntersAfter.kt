package com.payabli.example.app.ui.components

import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import kotlinx.coroutines.delay

/**
 * Fades and lifts its content into place after a delay.
 *
 * Used to stagger the parts of an outcome screen behind the mark that heads it, so a reader's eye
 * lands on the result before the sentence explaining it.
 *
 * Nothing is hidden while it waits: the content is laid out from the first frame and only its
 * appearance is animated, so a reader with animations turned off sees the finished screen and a
 * screen reader reaches every part of it either way.
 */
@Composable
fun ColumnScope.EntersAfter(
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

    AnimatedVisibility(
        visible = shown,
        enter =
            fadeIn(animationSpec = tween(ENTER_MILLIS)) +
                slideInVertically(
                    animationSpec = tween(ENTER_MILLIS, easing = FastOutSlowInEasing),
                    initialOffsetY = { it / 3 },
                ),
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
