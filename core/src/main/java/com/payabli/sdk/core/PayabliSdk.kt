package com.payabli.sdk.core

/** The SDK's own metadata. */
public object PayabliSdk {
    /** The version this build reports, equal to the version its artifacts are published under. */
    @JvmField
    public val VERSION: String = BuildConfig.SDK_VERSION
}
