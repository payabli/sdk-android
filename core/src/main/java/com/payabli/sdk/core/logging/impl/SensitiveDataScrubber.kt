package com.payabli.sdk.core.logging.impl

/**
 * Pattern rules over free text: the log message and every `Throwable.message`. Those positions have
 * no field name, so they cannot be allowlisted; this is the runtime net under them.
 *
 * It is a net, not a wall. Kotlin cannot prevent `"$pan"` reaching a `String` parameter, so a call
 * site that interpolates a value instead of passing a `LogField` is still a defect.
 *
 * Deliberate non-goal: no generic high-entropy or long-base64 rule. It would redact `sid`, which is
 * loggable by design and is the primary correlation handle. A scrubber that fights its callers gets
 * disabled.
 *
 * Regexes are compiled once at class initialisation, which the JVM makes thread-safe, and [Regex] is
 * itself immutable. No synchronisation is needed or wanted here.
 */
internal object SensitiveDataScrubber {
    private const val REDACTED = "[REDACTED]"

    /**
     * PAN floor. ISO/IEC 7812 permits PANs shorter than 13 digits and 12-digit PANs are issued in
     * practice, so a higher floor would miss real cardholder data.
     */
    private const val MIN_PAN_DIGITS = 12

    /**
     * A maximal digit run, possibly space or dash separated: PAN, bank account number, or similar.
     * The digit count is checked in [redactLongDigitRuns] rather than by the quantifier.
     *
     * The quantifier is over a single character class, not over a group. Java's `GroupCurly` recurses
     * once per greedy iteration, so the more precise `(?:[0-9][ -]?){11,}` overflows the stack on a
     * long run; `pathologicalInputCompletes` catches that. The lookarounds mean an over-long run is
     * consumed whole rather than partially replaced.
     */
    private val DIGIT_RUN = Regex("""(?<![0-9])[0-9][0-9 -]*[0-9](?![0-9])""")

    /** The platform's token type-tag prefixes, caught before a value ever reaches logcat. */
    private val TYPE_TAGGED_TOKEN = Regex("""\b(?:rt|at|act|pyb|sk|pk)_[A-Za-z0-9_-]{8,}""")

    /** A JWS/JWT in compact serialisation. `eyJ` is base64url of `{"`, so it starts every header. */
    private val COMPACT_JWS = Regex("""\beyJ[A-Za-z0-9_-]{4,}\.[A-Za-z0-9_-]{4,}\.[A-Za-z0-9_-]*""")

    private val BEARER_VALUE = Regex("""\bBearer\s+\S+""", RegexOption.IGNORE_CASE)

    private val PEM_BLOCK = Regex("""-----BEGIN [^-]*-----[\s\S]*?-----END [^-]*-----""")

    private val EMAIL = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")

    private val rules: List<Pair<Regex, String>> =
        listOf(
            TYPE_TAGGED_TOKEN to REDACTED,
            COMPACT_JWS to REDACTED,
            BEARER_VALUE to "Bearer $REDACTED",
            PEM_BLOCK to REDACTED,
            EMAIL to REDACTED,
        )

    /** Applies every rule in order and returns text safe to emit. */
    fun scrub(text: String): String {
        var scrubbed = redactLongDigitRuns(text)
        for ((pattern, replacement) in rules) {
            scrubbed = pattern.replace(scrubbed, replacement)
        }
        return scrubbed
    }

    private fun redactLongDigitRuns(text: String): String =
        DIGIT_RUN.replace(text) { match ->
            if (match.value.count { it in '0'..'9' } >= MIN_PAN_DIGITS) REDACTED else match.value
        }
}
