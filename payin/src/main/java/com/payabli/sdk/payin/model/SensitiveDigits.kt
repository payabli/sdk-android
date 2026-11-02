package com.payabli.sdk.payin.model

/**
 * A card number, security code or account number in a [CharArray] that [close] overwrites, which a `String` can't
 * be. Every member that makes one or reaches the digits is also `@JvmSynthetic`: `internal` alone compiles to a
 * public method a Java caller can name. Reads may run concurrently; [close] must not run beside one.
 */
internal class SensitiveDigits private constructor(
    private val digits: CharArray,
) : AutoCloseable {
    private var wiped: Boolean = false

    /** How many characters were supplied. Reads 0 after [close]. */
    @get:JvmSynthetic
    internal val length: Int get() = if (wiped) 0 else digits.size

    /**
     * Overwrites the digits. Idempotent. Afterwards the value reads as empty, so a request built from it fails
     * validation as a missing field.
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
     * A copy of the digits, which the caller overwrites. Readers go through [useDigits], which does that.
     *
     * @return a copy of the digits, empty once wiped.
     */
    @JvmSynthetic
    internal fun read(): CharArray = if (wiped) CharArray(0) else digits.copyOf()

    /**
     * Runs [block] over a copy of the digits and overwrites the copy before returning, including when [block]
     * throws.
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
     * The backing array as it stands, wiped or not, so a test can assert the overwrite happened; [read]
     * answers empty once wiped.
     *
     * @return the backing array as it stands, wiped or not.
     */
    @JvmSynthetic
    internal fun rawCopy(): CharArray = digits.copyOf()

    internal companion object {
        /**
         * NUL, as `Char(0)`. A NUL character literal puts a NUL byte in the source, and text tools then treat
         * the file as binary: `grep` reports no matches and does not say why.
         */
        internal val WIPED: Char = Char(0)

        /**
         * A value holding a copy of [source]. The `String` itself stays in memory until it is collected.
         *
         * @param source the digits as the host passed them.
         * @return the value holding the digits.
         */
        @JvmSynthetic
        internal fun ofString(source: String): SensitiveDigits = SensitiveDigits(source.toCharArray())
    }
}
