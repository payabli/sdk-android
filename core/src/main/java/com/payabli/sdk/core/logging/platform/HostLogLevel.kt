package com.payabli.sdk.core.logging.platform

import android.content.Context
import android.content.pm.ApplicationInfo
import com.payabli.sdk.core.logging.LoggerRegistry

/**
 * Sets the SDK's automatic log cutoff from the host application's debuggable flag, so a debug build
 * emits records without the integrator writing anything. An explicit `LoggerRegistry.setLogLevel`
 * still wins, in either order.
 *
 * Call with the application context: `applicationInfo` is read as given, and a context obtained
 * through `createPackageContext` would describe another package.
 *
 * Called from `PayabliSession.initialize`, which takes `applicationContext` from the host bindings
 * before calling this. Safe to call on every initialize: setting the automatic slot is idempotent and
 * cannot clobber an explicit level.
 */
internal fun Context.applyHostLogLevel() {
    LoggerRegistry.setHostDebuggable(
        (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0,
    )
}
