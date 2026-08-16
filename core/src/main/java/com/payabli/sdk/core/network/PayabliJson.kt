package com.payabli.sdk.core.network

import androidx.annotation.RestrictTo
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
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

    /**
     * Decodes [body], or returns null if it will not decode.
     *
     * For the envelopes whose callers treat an undecodable body as absent fields rather than as a
     * failure. One copy so the two cannot draw the boundary differently.
     *
     * Not `runCatching`: it catches `Throwable`, so an OutOfMemoryError would read as a decline.
     */
    internal fun <T> decodeOrNull(
        serializer: KSerializer<T>,
        body: String,
    ): T? =
        try {
            format.decodeFromString(serializer, body)
        } catch (malformed: SerializationException) {
            null
        }
}
