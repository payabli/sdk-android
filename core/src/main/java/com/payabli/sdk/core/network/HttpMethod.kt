package com.payabli.sdk.core.network

import androidx.annotation.RestrictTo

/**
 * HTTP verbs the SDK issues.
 *
 * @property wireName the verb as it goes on the wire. RFC 9110 method names are case-sensitive.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public enum class HttpMethod(
    public val wireName: String,
) {
    GET("GET"),
    POST("POST"),
    PATCH("PATCH"),
    PUT("PUT"),
    DELETE("DELETE"),
}
