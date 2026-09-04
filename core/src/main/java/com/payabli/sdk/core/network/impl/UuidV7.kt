package com.payabli.sdk.core.network.impl

import java.security.SecureRandom
import java.util.UUID

private const val VERSION = 7L
private const val VERSION_SHIFT = 12
private const val TIMESTAMP_SHIFT = 16
private const val TIMESTAMP_MASK = 0xFFFF_FFFF_FFFFL
private const val RAND_A_BOUND = 1 shl 12
private const val RAND_A_MASK = 0xFFFL
private const val VARIANT_RFC4122 = 2L
private const val VARIANT_SHIFT = 62
private const val RAND_B_MASK = 0x3FFF_FFFF_FFFF_FFFFL

/**
 * A version 7 UUID: a 48-bit millisecond timestamp, then randomness.
 *
 * Hand-rolled because `java.util.UUID` has no version 7 factory, and a dependency for one is not one this
 * SDK takes. The layout is RFC 9562 Section 5.7, and getting a field wrong is silent — the value still
 * parses and still looks like a UUID — which is why the bit positions are named rather than inlined and why
 * [UuidV7Test] asserts the version and variant fields rather than only that two values differ.
 *
 * Time-ordered, which is the property wanted here and the reason this is **not** the format for an
 * idempotency key: the leading timestamp narrows the guessable range and reports the device clock. An
 * identifier that only has to be traceable can pay that; one that guards against replay cannot.
 *
 * No intra-millisecond counter. RFC 9562 makes monotonicity within a millisecond optional, and the 74
 * random bits separate two values minted in the same one.
 */
internal object UuidV7 {
    private val random = SecureRandom()

    fun next(): UUID {
        val millis = System.currentTimeMillis()
        val randA = random.nextInt(RAND_A_BOUND).toLong()
        val high = ((millis and TIMESTAMP_MASK) shl TIMESTAMP_SHIFT) or (VERSION shl VERSION_SHIFT) or randA
        val low = (random.nextLong() and RAND_B_MASK) or (VARIANT_RFC4122 shl VARIANT_SHIFT)
        return UUID(high, low)
    }

    /** The millisecond a value was minted at, for a test that has to read the field back. */
    fun timestampMillisOf(uuid: UUID): Long = uuid.mostSignificantBits ushr TIMESTAMP_SHIFT

    /**
     * The two random fields, for a test that has to tell values apart without depending on their
     * timestamps.
     *
     * Read back rather than injected. What has to be proven is that these fields vary, and comparing them
     * directly proves it whatever the clock did, where forcing two values into one millisecond would need a
     * clock seam on this object and would still be proving the same thing.
     */
    fun randomFieldsOf(uuid: UUID): Pair<Long, Long> =
        (uuid.mostSignificantBits and RAND_A_MASK) to (uuid.leastSignificantBits and RAND_B_MASK)
}
