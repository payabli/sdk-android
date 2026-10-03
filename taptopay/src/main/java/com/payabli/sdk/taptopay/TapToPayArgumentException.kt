package com.payabli.sdk.taptopay

/** A charge argument this SDK refused before anything was sent. Its message is SDK text naming the argument. */
internal class TapToPayArgumentException(
    message: String,
) : IllegalArgumentException(message)

/** [require], raising the one argument refusal a host is told to correct. */
internal inline fun requireArgument(
    value: Boolean,
    message: () -> String,
) {
    if (!value) throw TapToPayArgumentException(message())
}

/** [requireNotNull], raising the one argument refusal a host is told to correct. */
internal inline fun <T : Any> requireArgumentNotNull(
    value: T?,
    message: () -> String,
): T = value ?: throw TapToPayArgumentException(message())
