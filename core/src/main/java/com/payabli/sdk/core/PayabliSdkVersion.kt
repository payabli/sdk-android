package com.payabli.sdk.core

import androidx.annotation.RestrictTo

/**
 * The SDK's own version, as every module reports it.
 *
 * One reader of the generated field, so a module that has to name the version does not have to enable a
 * `BuildConfig` of its own, and no second copy of the value can be introduced. The value comes from the same
 * `payabli.version` property the publish convention uses for the Maven coordinate, so the version an artifact
 * reports and the version it is published under cannot disagree.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public object PayabliSdkVersion {
    /** Semantic version of this build. */
    public val VALUE: String = BuildConfig.SDK_VERSION
}
