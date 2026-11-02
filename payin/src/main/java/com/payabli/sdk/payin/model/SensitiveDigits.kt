package com.payabli.sdk.payin.model

/**
 * A card number, security code or account number in a [CharArray] that [close] overwrites, which a `String` can't
 * be. Every member that makes one or reaches the digits is also `@JvmSynthetic`: `internal` alone compiles to a
 * public method a Java caller can name. Not thread-safe; one value belongs to one call.
 */
internal class SensitiveDigits private constructor(
    private val digits: CharArray,
) : AutoCloseable {
    private var wiped: Boolean = false

    /** How many characters were supplied. Reads 0 after [close]. */
    @get:JvmSynthetic
    internal val length: Int get() = if (wiped) 0 else digits.size

    /**
     * Overwrites the digits. Idempotent, so `use` closing a value a caller already closed is not an error.
     *
     * After this the value behaves as empty rather than throwing: a request built from a closed value fails
     * validation as a missing field, which is a better failure than an exception from inside a body writer.
     */
    override fun close() {
        digits.fill(WIPED)
        wiped = true
    }

    /**
     * Length only. The digits are what this type exists to keep out of a message.
     *
     * @return the length only, never the digits.
     */
    override fun toString(): String = "SensitiveDigits(length=$length)"

    /**
     * The digits, for a rule that reads them. Internal, so no caller can turn this back into a `String`.
     * Returns a copy, which the reader is responsible for: every use inside this module is a validator
     * that reads the copy and drops it.
     *
     * @return a copy of the digits, empty once wiped.
     */
    @JvmSynthetic
    internal fun read(): CharArray = if (wiped) CharArray(0) else digits.copyOf()

    /**
     * Runs [block] over the digits and overwrites the copy before returning, including when [block]
     * throws — the only way to read these that leaves nothing behind.
     *
     * @param block the rule to run over the digits.
     * @return what [block] returned, the copy overwritten first.
     */
    @JvmSynthetic
    internal fun <T> useDigits(block: (CharArray) -> T): T {
        val copy = read()
        try {
            return block(copy)
        } finally {
            copy.fill(WIPED)
        }
    }

    /**
     * A second value holding the same digits, which closing either one leaves the other's intact.
     *
     * @return the copy, empty when this one is wiped.
     */
    @JvmSynthetic
    internal fun copy(): SensitiveDigits = SensitiveDigits(read())

    /** True once [close] has run. */
    @get:JvmSynthetic
    internal val isWiped: Boolean get() = wiped

    /**
     * The backing array as it stands, wiped or not, so a test can assert the overwrite actually happened:
     * [read] answers empty once wiped, which is right for a reader and useless for proving the digits
     * are gone.
     *
     * @return the backing array as it stands, wiped or not.
     */
    @JvmSynthetic
    internal fun rawCopy(): CharArray = digits.copyOf()

    internal companion object {
        /**
         * NUL, built rather than written as a character literal.
         *
         * `Char(0)` because putting the character itself in this file puts a real NUL byte in the source: it
         * compiles, and then every text tool treats the file as binary, with `grep` reporting no matches at
         * all rather than saying why. Measured here, on this file, before this comment existed. `Char(0)`
         * costs the `const` modifier, which buys nothing on a private value.
         */
        internal val WIPED: Char = Char(0)

        /**
         * The value a host passed as a `String`. **This cannot unmake the `String` it is given**, and nothing can.
         *
         * @param source the digits as a `String`, which this cannot unmake.
         * @return the value holding the digits.
         */
        @JvmSynthetic
        internal fun ofString(source: String): SensitiveDigits = SensitiveDigits(source.toCharArray())
    }
}
