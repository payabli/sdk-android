package com.payabli.sdk.payin.util.extensions

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

/** Amounts carry two decimal places on this wire, and the service reads them as a decimal. */
internal const val AMOUNT_SCALE = 2

/** The largest mantissa a `decimal` holds, which is 96 bits, and the whole of the range check. */
private val MAX_MANTISSA = BigInteger("79228162514264337593543950335")

/**
 * Loose enough to round anything a caller could mean, tight enough that `setScale` cannot explode.
 *
 * Neither is the range check. They only keep the rounding below from raising on a value whose exponent
 * makes it unrepresentable in the first place.
 */
private const val MAX_ROUNDABLE_SCALE = 1_000L
private const val MAX_INTEGER_DIGITS = 1_000L

/**
 * The value as it will be written, so a check and the wire agree on what the amount is.
 *
 * Validation reads this rather than the value as supplied: `0.001` is more than zero and is sent as `0.00`.
 */
internal fun BigDecimal.atWireScale(): BigDecimal = setScale(AMOUNT_SCALE, RoundingMode.HALF_UP)

/**
 * The value as it will be sent, or null when the wire type could not hold it.
 *
 * The bound is on the **rounded** value, because that is what goes on the wire: `10.` followed by
 * twenty-nine zeros is sendable as `10.00`, and twenty-nine digits with cents is not, however either one
 * was written. These fields travel as a `decimal`, whose mantissa is 96 bits, so at two decimal places
 * the largest it holds is `792281625142643375935439503.35`. Nothing narrows that further beyond refusing
 * zero, so the type is the only bound there is.
 *
 * The two guards before the rounding exist only so the rounding itself cannot throw: `setScale` raises
 * `ArithmeticException` at both extremes of the exponent. Both read `precision` and `scale` rather than
 * the expanded value, so an absurd one costs nothing to refuse.
 */
internal fun BigDecimal.sendableOrNull(): BigDecimal? {
    // Zero rescales at any scale: rounding `BigDecimal.ZERO.setScale(Int.MAX_VALUE)` to two places
    // answers 0.00 without expanding, because zero short-circuits. The guards below would otherwise
    // refuse a fee of zero written with an extreme scale, and a fee of zero is a sendable value.
    if (signum() == 0) return atWireScale()
    if (scale().toLong() > MAX_ROUNDABLE_SCALE) return null
    if (precision().toLong() - scale().toLong() > MAX_INTEGER_DIGITS) return null
    val rounded = atWireScale()
    return rounded.takeIf { it.unscaledValue().abs() <= MAX_MANTISSA }
}
