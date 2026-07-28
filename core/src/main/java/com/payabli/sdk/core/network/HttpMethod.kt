package com.payabli.sdk.core.network

import androidx.annotation.RestrictTo

/**
 * HTTP verbs the SDK issues.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public enum class HttpMethod(
    /** The verb as it goes on the wire. RFC 9110 method names are case-sensitive. */
    public val wireName: String,
) {
    GET("GET"),
    POST("POST"),
    PATCH("PATCH"),
    PUT("PUT"),
    DELETE("DELETE"),
}
