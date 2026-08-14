package com.payabli.example.app.demo.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/**
 * The Material 3 type scale with three headings weighted up, and nothing else changed.
 *
 * No font files. The platform font is what an integrator sees before they apply their own.
 */
val PayabliTypography =
    Typography().let { base ->
        base.copy(
            headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
            titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        )
    }

/**
 * Monospace for values a reader may want to compare character by character: identifiers, amounts,
 * status codes, log lines and response bodies. Two helpers, because monospace marks a kind of content
 * and not a level of hierarchy, which is what the type scale is for.
 */
@Composable
@ReadOnlyComposable
fun monospaceBodyMedium(): TextStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)

@Composable
@ReadOnlyComposable
fun monospaceLabelSmall(): TextStyle = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace)
