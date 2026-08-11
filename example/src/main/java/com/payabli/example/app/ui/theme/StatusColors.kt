package com.payabli.example.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The two colours Material 3 has no role for.
 *
 * A readiness check that passes and one that warns are different results, and the scheme offers
 * `error` for the third but nothing for the first two. Red stays `colorScheme.error`, which the
 * palette already fills with Cinnamon. Only success and warning are added.
 *
 * `neutral` is the resting tone for a state that is neither good nor bad, such as a session that has
 * not started, or a check that could not be run.
 */
@Immutable
data class StatusColors(
    val success: Color,
    val warning: Color,
    val neutral: Color,
)

/**
 * Teal for success, because the style guide has no green.
 *
 * Teal is the nearest thing the palette offers to "this is fine", and every colour here appears in
 * the guide's token list.
 *
 * Measured on the surfaces these sit on: success 7.6:1 and warning 8.0:1 on a card, 8.2:1 and 8.7:1
 * on the page.
 */
val LightStatusColors =
    StatusColors(
        success = PayabliTeal4,
        warning = PayabliLemon4,
        neutral = PayabliNeutral4,
    )

/** 14.2:1 and 10.8:1 on a card; 16.7:1 and 12.7:1 on the page. */
val DarkStatusColors =
    StatusColors(
        success = PayabliTeal2,
        warning = PayabliLemon1,
        neutral = PayabliNeutral5,
    )

/**
 * Static: this changes only when the whole theme changes, so there is nothing to gain from tracking
 * reads of it individually.
 */
val LocalStatusColors = staticCompositionLocalOf { LightStatusColors }
