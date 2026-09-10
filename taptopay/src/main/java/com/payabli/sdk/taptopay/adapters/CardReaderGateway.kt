package com.payabli.sdk.taptopay.adapters

/**
 * The card reader vendor's SDK, reduced to the two calls this module makes.
 *
 * No vendor type crosses this line, so everything above it is reachable from a unit test.
 */
internal interface CardReaderGateway {
    /** Brings the reader up. Fails with [CardReaderFailure]. */
    suspend fun prepareReader(config: ReaderArming)

    /** Runs one payment: the tap, and the charge that follows it. Fails with [CardReaderFailure]. */
    suspend fun startReading(request: ReaderCharge): ChargeRecord
}

/**
 * What the vendor reported, reduced to what this module acts on.
 *
 * [kind] is the only part a decision may be taken on and [code] is a fixed vocabulary. Both are safe to
 * log and to report.
 *
 * **The vendor's free text is not carried.** This failure reaches a host as `TapToPayException.cause.cause`,
 * and `internal` is a Kotlin boundary rather than a JVM one, so every property here is a public getter that
 * a Java caller or a field-inspecting crash reporter can read. The vendor's prose is outside this SDK's
 * control and can echo what was sent to it, which for arming is the reader's API credentials. Nothing in
 * this module reads it: the log line carries the kind and the code, telemetry has a test asserting the
 * vendor's words reach no property, and the diagnostic tier reads them from the vendor's own exception.
 */
internal class CardReaderFailure(
    val kind: ReaderFailureKind,
    val code: String? = null,
    cause: Throwable? = null,
) : Exception("card reader error ${code ?: kind.diagnosticName}", cause)

/** Which reader failure it was, in terms of what can be done about it. */
internal enum class ReaderFailureKind {
    /** The reader holds no usable session. Only bringing it up again fixes this. */
    SESSION_UNUSABLE,

    /** The contactless radio is off, or this handset has none. */
    CONTACTLESS_UNAVAILABLE,

    /** The reader did not answer inside the time it was given. */
    TIMED_OUT,

    /** The vendor refused this handset. The refusal is a state it holds, so repeating the call is not it. */
    DEVICE_DENIED,

    /**
     * The vendor refused the handset with a code it has not published a meaning for.
     *
     * Terminal like [DEVICE_DENIED], on observation rather than on the vendor saying so. Separate so a
     * refusal with no stated meaning is legible as one, and so moving a code between the two is one edit.
     */
    DEVICE_DENIED_UNCONFIRMED,

    UNCLASSIFIED,
    ;

    val diagnosticName: String get() = name.lowercase()
}
