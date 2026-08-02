package com.payabli.sdk.core.logging

/**
 * Synthetic vectors. Nothing in this file may be mistaken for, or copied as, real cardholder data
 * The never-log rule extends to a test fixture that could be copied.
 *
 * Every digit run below is Luhn-invalid and made of the digit 9, so it falls in the IIN range ISO/IEC
 * 7812 reserves for national use and which no card scheme issues from.
 * `SdkLoggerRedactionTest.luhnValidityIsNotAccidental` asserts the Luhn property, so this is a
 * machine-checked guarantee rather than a comment that can rot.
 *
 * Scheme test numbers such as the well-known Visa and Mastercard ones are deliberately absent: they
 * are Luhn-valid, sit in live issuer ranges, are exactly what card-data scanners flag, and are
 * paste-ready into a real form.
 */
internal object LogFixtures {
    /** Eleven digits, below the scrubber's floor, so it must survive untouched. */
    const val DIGITS_11: String = "99999999999"

    /** Twelve digits, at the floor, so it must be redacted. */
    const val DIGITS_12: String = "999999999999"

    const val DIGITS_16: String = "9999999999999999"

    const val DIGITS_16_SPACED: String = "9999 9999 9999 9999"

    const val DIGITS_16_DASHED: String = "9999-9999-9999-9999"

    /** Over-long run: the anchored pattern must consume all of it, leaving no residual digits. */
    const val DIGITS_25: String = "9999999999999999999999999"

    /** Accepted false positive, pinned deliberately. Log ISO-8601 or a duration, never epoch millis. */
    const val EPOCH_MILLIS_13: String = "1753600000000"

    /** Three digits: no pattern can identify this. Only the allowlist protects it, which is the point. */
    const val CVV: String = "000"

    const val ACH_ACCOUNT: String = "999999999"

    /** ABA checksum invalid, and 999 is not a Federal Reserve routing symbol. */
    const val ROUTING: String = "999999999"

    /** An impossible month and year. */
    const val EXPIRY: String = "0000"

    const val CARDHOLDER: String = "Ada Example"

    /** RFC 2606 reserved TLD, so it is unroutable by definition. */
    const val EMAIL: String = "nobody@example.invalid"

    /** Shape only: the type tag plus filler, no entropy and no signature. */
    const val REFRESH_TOKEN: String = "rt_zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz"

    /** Shape only: three base64url-looking segments, none of which decode to anything. */
    const val ACCESS_JWT: String = "eyJzzzzzzzzzz.zzzzzzzzzz.zzzzzzzzzz"

    // The header literal is split so no contiguous `BEGIN ... PRIVATE KEY` string exists in this
    // file for a secret scanner to flag on every future scan of the repository.
    const val PEM_BLOCK: String =
        "-----BEGIN " + "PRIVATE KEY" + "-----\nzzzzzzzz\n-----END " + "PRIVATE KEY" + "-----"

    /**
     * Allowlisted values are chosen so that no sensitive fixture above is a substring of them:
     * `sid=sess-0001` would contain [CVV] and make an `assertFalse(contains(...))` pass vacuously.
     */
    const val SID: String = "sess-a1b2c3"

    const val ROUTE: String = "/capture"
}
