package com.payabli.example.app.ui.components

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.payabli.example.app.ui.theme.LocalStatusColors
import kotlinx.coroutines.launch

/**
 * The mark shown when something succeeded.
 *
 * It settles rather than appearing: the tick springs in past its final size and back, and a ring
 * expands out from behind it and fades. A payment finishing is the one moment in this app worth
 * marking, and a static glyph gives a reader no signal that anything just happened.
 *
 * The motion is decorative, so the whole thing carries no content description: the sentence beside it
 * already says what happened, and a screen reader announcing an icon as well would say it twice.
 *
 * @param contentDescription set only if this mark is ever used without text beside it.
 */
@Composable
fun SuccessMark(
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
    tint: Color = LocalStatusColors.current.success,
    contentDescription: String? = null,
) {
    val animate = animationsAreOn()
    // Start settled when animations are off, so the mark is correct on the first frame instead of
    // being animated to the same place over zero milliseconds.
    val scale = remember { Animatable(if (animate) INITIAL_SCALE else 1f) }
    val ring = remember { Animatable(if (animate) 0f else 1f) }

    LaunchedEffect(animate) {
        if (!animate) return@LaunchedEffect
        launch {
            scale.animateTo(
                targetValue = 1f,
                animationSpec =
                    spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessLow,
                    ),
            )
        }
        launch {
            ring.animateTo(1f, animationSpec = tween(RING_MILLIS, easing = FastOutSlowInEasing))
        }
    }

    Box(
        modifier = modifier.size(size * RING_MAX_SCALE),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size).clearAndSetSemantics { }) {
            val progress = ring.value
            if (progress > 0f && progress < 1f) {
                drawCircle(
                    color = tint,
                    radius = this.size.minDimension / 2f * (1f + (RING_MAX_SCALE - 1f) * progress),
                    alpha = RING_START_ALPHA * (1f - progress),
                )
            }
        }
        Icon(
            imageVector = DemoIcons.Pass,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(size).scale(scale.value),
        )
    }
}

/**
 * Whether to animate at all.
 *
 * Reads the system animator duration scale, which is 0 when someone has turned animations off in
 * developer options or in accessibility settings. Motion is the first thing to drop for a reader who
 * has asked for less of it.
 *
 * Previews report off, because a preview renders one frame and a spring caught mid-flight would show
 * a tick at the wrong size.
 */
@Composable
private fun animationsAreOn(): Boolean {
    if (LocalInspectionMode.current) return false
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }
}

private const val INITIAL_SCALE = 0.5f
private const val RING_MAX_SCALE = 1.7f
private const val RING_START_ALPHA = 0.35f
private const val RING_MILLIS = 700

@PreviewLightDark
@Composable
private fun SuccessMarkPreview() {
    PreviewSurface {
        SuccessMark()
        SuccessMark(size = 36.dp)
    }
}
