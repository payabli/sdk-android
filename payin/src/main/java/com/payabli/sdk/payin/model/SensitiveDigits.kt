package com.payabli.sdk.payin.model

/**
 * A card number, security code or bank account number, held so it can be overwritten.
 *
 * A `String` cannot be erased: it is immutable, the collector may move it, and nothing can say when the
 * last copy is gone. This holds the digits in a [CharArray] instead, and [close] overwrites them.
 *
 * ```kotlin
 * SensitiveDigits.of(typed).use { pan ->
 *     client.capture(entry, request(pan))
 * }
 * ```
 *
 * **The guarantee starts here and runs to the socket, not from the keystroke.** Whoever supplies the digits
 * decides what they were held in before this, and a text field holds its value as a `String`. What this type
 * adds is that the SDK keeps no `String` of its own, exposes no accessor that would make one, and leaves the
 * buffers it owns overwritten once the request has been written. Encrypting at the field is what closes the
 * rest, and that is not this type's job.
 *
 * Not thread-safe. One value belongs to one call.
 */
public class SensitiveDigits private constructor(
    private val digits: CharArray,
) : AutoCloseable {
    private var wiped: Boolean = false

    /** How many characters were supplied. Reads 0 after [close]. */
    public val length: Int get() = if (wiped) 0 else digits.size

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

    /** Length only. The digits are what this type exists to keep out of a message. */
    override fun toString(): String = "SensitiveDigits(length=$length)"

    /**
     * The digits, for a rule that reads them. Internal, so no caller can turn this back into a `String`.
     *
     * Returns a copy, which the reader is responsible for. Every use inside this module is a validator that
     * reads the copy and drops it, and validators are pure functions over characters.
     */
    internal fun read(): CharArray = if (wiped) CharArray(0) else digits.copyOf()

    /**
     * Runs [block] over the digits and overwrites the copy before returning, including when [block] throws.
     *
     * The only way to read these that leaves nothing behind. [read] hands out a copy the caller then owns, and
     * a caller that throws mid-validation never gets to wipe it.
     */
    internal fun <T> useDigits(block: (CharArray) -> T): T {
        val copy = read()
        try {
            return block(copy)
        } finally {
            copy.fill(WIPED)
        }
    }

    /** True once [close] has run. */
    internal val isWiped: Boolean get() = wiped

    /**
     * The backing array as it stands, wiped or not, so a test can assert the overwrite actually happened.
     *
     * [read] answers empty once wiped, which is right for a reader and useless for proving the digits are
     * gone.
     */
    internal fun rawCopy(): CharArray = digits.copyOf()

    public companion object {
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
         * Takes a copy of [source], which the caller still owns and should overwrite in turn.
         *
         * A copy, so a caller reusing its buffer for the next field cannot change a body being built.
         */
        public fun of(source: CharArray): SensitiveDigits = SensitiveDigits(source.copyOf())

        /**
         * For a value that is already a `String`, typically from a text field.
         *
         * **This cannot unmake the `String` it is given**, and no overload can. It is here because the form
         * layer holds its values that way and the alternative is every caller writing the same conversion.
         * Where the digits are assembled by the caller, prefer [of] with a [CharArray] and never build the
         * `String` at all.
         */
        public fun ofString(source: String): SensitiveDigits = SensitiveDigits(source.toCharArray())
    }
}
