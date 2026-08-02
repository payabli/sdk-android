package com.payabli.sdk.core.logging.platform

import android.content.Context
import android.content.pm.ApplicationInfo
import com.payabli.sdk.core.logging.PayabliLoggers

/**
 * Sets the SDK's automatic log cutoff from the host application's debuggable flag, so a debug build
 * emits records without the integrator writing anything. An explicit `PayabliLoggers.setLogLevel`
 * still wins, in either order.
 *
 * Call with the application context: `applicationInfo` is read as given, and a context obtained
 * through `createPackageContext` would describe another package.
 *
 * No caller yet, by design. The SDK holds no `Context` until `initialize` exists.
 */
internal fun Context.applyHostLogLevel() {
    PayabliLoggers.setHostDebuggable(
        (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0,
    )
}
