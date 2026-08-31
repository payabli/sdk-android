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
 * What the vendor reported, in full.
 *
 * [kind] is the only part a decision may be taken on. [detail] is their `message`, renamed because
 * `Throwable.message` is taken. [code] is a fixed vocabulary; the other three are free text.
 */
internal class CardReaderFailure(
    val kind: ReaderFailureKind,
    val code: String? = null,
    val type: String? = null,
    val field: String? = null,
    val detail: String? = null,
    val additionalInfo: String? = null,
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
     * refusal we cannot explain is legible as one, and so moving a code between the two is one edit.
     */
    DEVICE_DENIED_UNCONFIRMED,

    UNCLASSIFIED,
    ;

    val diagnosticName: String get() = name.lowercase()
}
