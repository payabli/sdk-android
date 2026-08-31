package com.payabli.sdk.telemetry

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Calls [onBackground] when the app's last visible screen goes away.
 *
 * A backgrounded process can be killed without another line of its code running, so what is queued then is
 * lost unless it leaves now.
 *
 * `onStop`, not `onPause`: a dialog over the app pauses it and there is still somewhere for a batch to go.
 */
internal class AppBackgroundWatcher(
    private val onBackground: () -> Unit,
) : DefaultLifecycleObserver {
    override fun onStop(owner: LifecycleOwner) {
        onBackground()
    }
}
