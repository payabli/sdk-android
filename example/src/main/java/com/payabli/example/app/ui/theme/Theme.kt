package com.payabli.example.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * There is no dynamic-colour option.
 *
 * Dynamic colour derives the whole scheme from the user's wallpaper, so on any Android 12+ device it
 * would put the Payabli brand out of a demo whose job is to show it, and would make two screenshots
 * of the same screen on two devices incomparable. An integrator wanting to see their own scheme
 * builds `MaterialTheme` with `dynamicLightColorScheme` in their own app, which is where the choice
 * belongs.
 */
@Composable
fun PayabliDemoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) PayabliDarkColorScheme else PayabliLightColorScheme
    val statusColors = if (darkTheme) DarkStatusColors else LightStatusColors

    CompositionLocalProvider(LocalStatusColors provides statusColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = PayabliTypography,
            content = content,
        )
    }
}
