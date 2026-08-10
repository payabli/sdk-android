package com.payabli.example.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalWindowInfo
import kotlinx.coroutines.flow.filter

/**
 * Runs [onRecheck] whenever this window takes focus, and once on entry.
 *
 * The device checks are read when a screen's model is created, and NFC is the one input to them that
 * changes from outside the app. Focus rather than resume, because the quick settings shade takes
 * window focus without pausing the activity: a toggle from there can be made and undone with resume
 * never firing. Returning from the settings screen restores focus too, so this covers both routes.
 */
@Composable
fun RecheckWhenFocused(onRecheck: () -> Unit) {
    val window = LocalWindowInfo.current
    LaunchedEffect(window) {
        snapshotFlow { window.isWindowFocused }
            .filter { it }
            .collect { onRecheck() }
    }
}
