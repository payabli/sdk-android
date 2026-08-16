package com.payabli.sdk.core.network

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Stands in for a JVM error raised mid-decode, which no real body can provoke on demand. */
internal class SimulatedFatalError : Error()

/**
 * A serializer that fails the way the JVM does, not the way a bad body does.
 *
 * Shared because every decode boundary in this package owes the same guarantee, and the serializers
 * those boundaries use in production are fixed.
 */
internal object FatalSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FatalSerializer", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String = throw SimulatedFatalError()

    override fun serialize(
        encoder: Encoder,
        value: String,
    ): Unit = throw UnsupportedOperationException("decode-only")
}
