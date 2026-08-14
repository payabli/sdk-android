package com.payabli.example.app.demo.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * There is no dynamic-colour option.
 *
 * Dynamic colour derives the whole scheme from the user's wallpaper on Android 12+, which replaces
 * the brand this app exists to show and makes two devices' screenshots incomparable. An integrator
 * seeing their own scheme builds `MaterialTheme` with `dynamicLightColorScheme` in their own app.
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
