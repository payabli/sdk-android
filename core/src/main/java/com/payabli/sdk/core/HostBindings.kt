package com.payabli.sdk.core

import android.content.Context

/**
 * What the SDK needs from the host application itself, as opposed to from its Payabli configuration.
 *
 * Separate from `PayabliConfig` because the two answer different questions: a configuration says which
 * merchant and which environment, a binding says which app process. The split
 * keeps a configuration a value that can be built, compared and logged without holding a framework
 * reference.
 */
public class HostBindings(
    /**
     * The host application's context, read for whether the build is debuggable, which derives the automatic
     * diagnostic log level.
     *
     * `initialize` takes `applicationContext` from whatever is passed, so an Activity is corrected. A context
     * from `createPackageContext` is not, and would describe another package's build.
     */
    public val appContext: Context,
)
