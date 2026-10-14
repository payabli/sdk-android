package com.payabli.sdk.core

import android.content.Context

/**
 * What the SDK needs from the host application itself, as opposed to from its Payabli configuration.
 *
 * Separate from `PayabliConfig` because the two answer different questions: a configuration says which
 * merchant and which environment, a binding says which app process. The split
 * keeps a configuration a value that can be built, compared and logged without holding a framework
 * reference.
 *
 * @property appContext the host application's context, read for whether the build is debuggable.
 *   `initialize` takes `applicationContext` from whatever is passed, so an Activity is corrected; a
 *   `createPackageContext` context is not, and would describe another package's build.
 */
public class HostBindings(
    public val appContext: Context,
)
