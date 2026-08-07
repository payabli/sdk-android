package com.payabli.example.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * @param dynamicColor off by default, unlike the Android Studio template it replaces. Dynamic colour
 *   derives the whole scheme from the user's wallpaper, so on any Android 12+ device it would put
 *   the Payabli brand out of a demo whose job is to show it, and would make two screenshots of the
 *   same screen on two devices incomparable. The parameter stays so an integrator can turn it on and
 *   see what their own app would look like.
 */
@Composable
fun PayabliDemoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
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
