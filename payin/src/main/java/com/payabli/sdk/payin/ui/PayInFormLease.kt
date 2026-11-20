package com.payabli.sdk.payin.ui

import android.content.pm.ApplicationInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/** Whether a second form over a drawn flow throws; null reads it from whether the host app is debuggable. */
internal val LocalPayInFormStopsOnASecondForm = staticCompositionLocalOf<Boolean?> { null }

/** Which form draws a flow. One at a time; read and written on the main thread only. */
internal class PayInFormLease {
    internal var holder: Any? by mutableStateOf(null)
}

/**
 * Holds [lease] for one form from the composition that takes it until the form leaves or is abandoned. A form that
 * replaces another over the flow in one composition takes it when that composition is applied, after the other has
 * given it back, so only a form still waiting after that is a second form.
 */
private class LeaseHold(
    private val lease: PayInFormLease,
    private val stops: Boolean,
) : RememberObserver {
    init {
        takeIfFree()
    }

    fun takeIfFree() {
        if (lease.holder == null) lease.holder = this
    }

    override fun onRemembered() {
        takeIfFree()
        check(lease.holder === this || !stops) {
            "One PayabliPayIn is drawn by two PayabliPayInForms. A flow is drawn by one form at a time; give each form its own."
        }
    }

    override fun onForgotten() = release()

    override fun onAbandoned() = release()

    private fun release() {
        if (lease.holder === this) lease.holder = null
    }
}

/**
 * Whether this form draws the flow [lease] belongs to. A second form over a drawn flow is a host's mistake: a
 * debuggable app stops on it, and otherwise the form waits, inert, and takes the flow once the first leaves.
 *
 * @param lease the lease of the flow the form is handed.
 * @return true when this form draws the flow; false while another does.
 * @throws IllegalStateException when another form still draws the flow once this one is applied, in a debuggable app.
 */
@Composable
internal fun rememberPayInFormLease(lease: PayInFormLease): Boolean {
    val stops =
        LocalPayInFormStopsOnASecondForm.current
            ?: (LocalContext.current.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
    val hold = remember(lease) { LeaseHold(lease, stops) }
    // A waiting form takes the flow on the frame after the first one leaves.
    SideEffect { hold.takeIfFree() }
    return lease.holder === hold
}
