package com.payabli.sdk.core.network

import androidx.annotation.RestrictTo
import kotlinx.serialization.json.Json

/**
 * The single JSON codec for every Payabli wire shape.
 *
 * One instance so encode and decode settings cannot drift between endpoint clients, and because
 * `Json` is designed to be created once and reused.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public object PayabliJson {
    public val format: Json =
        Json {
            // Payabli adds response fields without notice; an unknown key must not fail a decode.
            ignoreUnknownKeys = true
            // Absent JSON keys fall back to Kotlin defaults rather than throwing.
            explicitNulls = false
            // Never pretty-print: request bodies are compact, and pretty output widens log surface.
            prettyPrint = false
        }
}
