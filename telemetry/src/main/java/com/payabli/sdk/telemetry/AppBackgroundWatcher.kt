package com.payabli.sdk.telemetry

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * Calls [onBackground] the moment the app's last visible screen goes away.
 *
 * That moment matters more than it looks: a backgrounded process can be killed without another line of its
 * code running, so anything still queued then is lost unless it leaves now.
 *
 * Counting started activities rather than watching one of them is what makes a rotation and a move between
 * two screens quiet: both stop one activity only after starting the next, so the count never reaches zero.
 *
 * No synchronization, because these callbacks are delivered on the main thread and nothing else touches the
 * count.
 */
internal class AppBackgroundWatcher(
    private val onBackground: () -> Unit,
) : Application.ActivityLifecycleCallbacks {
    private var visible = 0

    override fun onActivityStarted(activity: Activity) {
        visible++
    }

    override fun onActivityStopped(activity: Activity) {
        visible--
        if (visible <= 0) {
            visible = 0
            onBackground()
        }
    }

    override fun onActivityCreated(
        activity: Activity,
        savedInstanceState: Bundle?,
    ) = Unit

    override fun onActivityResumed(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(
        activity: Activity,
        outState: Bundle,
    ) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
}
