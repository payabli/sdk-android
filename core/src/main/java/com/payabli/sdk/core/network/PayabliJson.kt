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
     * Decodes [body], or returns null if it will not decode, for the envelopes whose callers treat an
     * undecodable body as absent fields rather than a failure. Not `runCatching`: it catches `Throwable`,
     * so an OutOfMemoryError would read as a decline.
     *
     * @param serializer the generated serializer for [T].
     * @param body the wire text to decode.
     * @return the decoded value, or null when the body will not decode as [T].
     */
    internal fun <T> decodeOrNull(
        serializer: KSerializer<T>,
        body: String,
    ): T? =
        try {
            format.decodeFromString(serializer, body)
        } catch (_: SerializationException) {
            null
        }
}
