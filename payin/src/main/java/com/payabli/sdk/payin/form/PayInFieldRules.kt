package com.payabli.sdk.payin.form

import com.payabli.sdk.payin.model.SensitiveDigits

/**
 * What each field accepts, and what is wrong with it when it does not.
 *
 * Pure functions over strings: no Android, no Compose, no state, no logging. The caller holds the
 * text.
 *
 * Every check here catches something the API also rejects, so none refuses a value the server
 * accepts.
 */
public object PayInFieldRules {
    /** Digits only, no separators. Callers strip formatting before asking. */
    private val DIGITS = Regex("^[0-9]*$")

    // A postal code is length-limited and nothing more: ZIP+4, Canadian and British codes are all
    // accepted by the API.

    private const val CARD_NUMBER_MIN = 12
    private const val CARD_NUMBER_MAX = 19
    private const val POSTAL_CODE_MAX = 12
    private const val ROUTING_NUMBER_LENGTH = 9
    private val SECURITY_CODE_RANGE = 3..4
    private val ACCOUNT_NUMBER_RANGE = 4..17

    /** Fields a payer may leave blank. Everything else is required once it is on the form. */
    private val OPTIONAL =
        setOf(
            PayInField.Amount,
            PayInField.ServiceFee,
            PayInField.MethodDescription,
            PayInField.CustomerNumber,
            PayInField.DeviceId,
        )

    private val CARD_INSTRUMENT =
        setOf(
            PayInField.CardNumber,
            PayInField.CardExpiration,
            PayInField.CardSecurityCode,
            PayInField.CardholderName,
            PayInField.CardPostalCode,
        )

    private val BANK_INSTRUMENT =
        setOf(
            PayInField.AccountNumber,
            PayInField.RoutingNumber,
            PayInField.AccountHolder,
        )

    /**
     * The fields an instrument of this method cannot be built without, whichever fields a form renders.
     *
     * [missing] answers requiredness for a field that is on the form. These are required of the *form*: the
     * request carries no absent value for any of them, so a method offered without one can never submit.
     * `PayInInstrumentFieldsTest` holds this to what the client refuses, one field at a time.
     *
     * A copy per call. Kotlin's read-only `Set` is a `LinkedHashSet` at runtime, so the set behind this is one
     * a Java caller can clear, and the configuration check reads it on every construction.
     */
    public fun instrumentFields(method: PayInMethodType): Set<PayInField> =
        when (method) {
            PayInMethodType.Card -> CARD_INSTRUMENT
            PayInMethodType.BankAccount -> BANK_INSTRUMENT
        }.toSet()

    /** How many characters a field will accept, after formatting is stripped. */
    public fun maxLength(field: PayInField): Int? =
        when (field) {
            PayInField.CardNumber -> CARD_NUMBER_MAX
            PayInField.CardSecurityCode -> SECURITY_CODE_RANGE.last
            PayInField.CardPostalCode, PayInField.BillingPostalCode -> POSTAL_CODE_MAX
            PayInField.RoutingNumber -> ROUTING_NUMBER_LENGTH
            PayInField.AccountNumber -> ACCOUNT_NUMBER_RANGE.last
            else -> null
        }

    /** True when the field takes digits and nothing else. */
    public fun isDigitsOnly(field: PayInField): Boolean =
        when (field) {
            PayInField.CardNumber,
            PayInField.CardSecurityCode,
            PayInField.RoutingNumber,
            PayInField.AccountNumber,
            -> true

            else -> false
        }

    /**
     * Drops anything the field will not take, then truncates.
     *
     * Applied to the value, because a keyboard type is only a hint: a paste, an autofill entry and a
     * hardware keyboard all ignore it.
     */
    public fun filter(
        field: PayInField,
        raw: String,
    ): String {
        // ASCII, matching DIGITS below. Char.isDigit keeps Arabic-Indic and Devanagari digits, which
        // then fail the rules as DigitsOnly with no way for a payer to tell what is wrong.
        val kept = if (isDigitsOnly(field)) raw.filter { it in '0'..'9' } else raw
        val limit = maxLength(field)
        return if (limit != null && kept.length > limit) kept.take(limit) else kept
    }

    /**
     * What is wrong with this value, or null when nothing is.
     *
     * A blank value returns null. [missing] answers whether a required field is still empty.
     *
     * @param today when given, the expiry is also checked against it.
     */
    public fun error(
        field: PayInField,
        value: String,
        today: ExpiryValue? = null,
    ): PayInFieldError? {
        if (value.isBlank()) return null
        return when (field) {
            PayInField.CardExpiration -> expiryError(value, today)
            PayInField.BillingEmail -> emailError(value)
            // The counted and checksummed fields, and only those: one implementation reached from both
            // overloads, and the copy is wiped, since the caller's `String` cannot be erased but this one can.
            // Every other field answers null without reading the value, and the form asks about all of them on
            // each recomposition, so converting them would allocate on every keystroke for no answer.
            PayInField.CardNumber,
            PayInField.CardSecurityCode,
            PayInField.CardPostalCode,
            PayInField.BillingPostalCode,
            PayInField.RoutingNumber,
            PayInField.AccountNumber,
            -> value.toCharArray().useAndWipe { error(field, it, today) }

            else -> null
        }
    }

    /**
     * The same answer for a value held in a buffer rather than a `String`.
     *
     * Here so a card number or an account number can be checked without being turned into a `String` that
     * cannot afterwards be erased. An expiry and an email address are the two this overload cannot answer
     * without one, and they build it because their validators are text-based.
     */
    public fun error(
        field: PayInField,
        value: CharArray,
        today: ExpiryValue? = null,
    ): PayInFieldError? {
        if (value.isEmpty() || value.all { it.isWhitespace() }) return null
        return when (field) {
            PayInField.CardNumber -> cardNumberError(value)
            PayInField.CardPostalCode, PayInField.BillingPostalCode ->
                if (value.size > POSTAL_CODE_MAX) PayInFieldError.TooManyCharacters(POSTAL_CODE_MAX) else null

            PayInField.CardSecurityCode -> rangeError(value, SECURITY_CODE_RANGE)
            PayInField.RoutingNumber -> routingNumberError(value)
            PayInField.AccountNumber -> rangeError(value, ACCOUNT_NUMBER_RANGE)
            PayInField.CardExpiration, PayInField.BillingEmail -> error(field, String(value), today)
            else -> null
        }
    }

    /** True when the field must be filled in and is not. */
    public fun missing(
        field: PayInField,
        value: String,
    ): Boolean = field !in OPTIONAL && value.isBlank()

    /**
     * The Luhn check digit, which every card scheme's numbers satisfy.
     *
     * Says a number is well formed, never that an account exists.
     */
    public fun passesLuhn(digits: String): Boolean = digits.toCharArray().useAndWipe { passesLuhn(it) }

    /** As [passesLuhn], for a number held in a buffer rather than a `String`. */
    public fun passesLuhn(digits: CharArray): Boolean {
        if (digits.isEmpty() || !isAllDigits(digits)) return false
        var sum = 0
        var double = false
        for (index in digits.lastIndex downTo 0) {
            var value = digits[index] - '0'
            if (double) {
                value *= 2
                if (value > 9) value -= 9
            }
            sum += value
            double = !double
        }
        return sum % 10 == 0
    }

    /** The ABA routing checksum: weights 3, 7, 1 repeating, and the total is a multiple of ten. */
    public fun passesAbaChecksum(digits: String): Boolean = digits.toCharArray().useAndWipe { passesAbaChecksum(it) }

    /** As [passesAbaChecksum], for a number held in a buffer rather than a `String`. */
    public fun passesAbaChecksum(digits: CharArray): Boolean {
        if (digits.size != ROUTING_NUMBER_LENGTH || !isAllDigits(digits)) return false
        val weights = intArrayOf(3, 7, 1, 3, 7, 1, 3, 7, 1)
        val sum = digits.indices.sumOf { (digits[it] - '0') * weights[it] }
        return sum % 10 == 0
    }

    /**
     * Runs [block] over the copy and overwrites it, whichever way [block] ends.
     *
     * Every `String` overload here makes one of these, and a card number in it is as erasable as any other
     * buffer even though the `String` it came from is not.
     */
    private inline fun <T> CharArray.useAndWipe(block: (CharArray) -> T): T {
        try {
            return block(this)
        } finally {
            fill(SensitiveDigits.WIPED)
        }
    }

    /** ASCII digits, matching [DIGITS], which the `String` overloads used before the buffer ones existed. */
    internal fun isAllDigits(value: CharArray): Boolean = value.all { it in '0'..'9' }

    private fun cardNumberError(value: CharArray): PayInFieldError? {
        if (!isAllDigits(value)) return PayInFieldError.DigitsOnly
        // Short while it is still being typed, so it keeps its own message. Long only ever arrives
        // through this function, since the field truncates at maxLength.
        if (value.size < CARD_NUMBER_MIN) return PayInFieldError.ShorterThan(CARD_NUMBER_MIN)
        if (value.size > CARD_NUMBER_MAX) return PayInFieldError.LongerThan(CARD_NUMBER_MAX)
        if (!passesLuhn(value)) return PayInFieldError.CardNumberNotValid
        return null
    }

    private fun routingNumberError(value: CharArray): PayInFieldError? {
        exactLengthError(value, ROUTING_NUMBER_LENGTH)?.let { return it }
        if (!passesAbaChecksum(value)) return PayInFieldError.RoutingNumberNotValid
        return null
    }

    private fun exactLengthError(
        value: CharArray,
        length: Int,
    ): PayInFieldError? {
        if (!isAllDigits(value)) return PayInFieldError.DigitsOnly
        if (value.size != length) return PayInFieldError.NotExactly(length)
        return null
    }

    private fun rangeError(
        value: CharArray,
        allowed: IntRange,
    ): PayInFieldError? {
        if (!isAllDigits(value)) return PayInFieldError.DigitsOnly
        if (value.size !in allowed) return PayInFieldError.OutsideRange(allowed.first, allowed.last)
        return null
    }

    /**
     * One at-sign, something before it, and a dot in what follows.
     *
     * Whether the address exists is the mail server's answer.
     */
    private fun emailError(value: String): PayInFieldError? {
        if (value.any { it.isWhitespace() }) return PayInFieldError.EmailNotValid
        val at = value.indexOf('@')
        val domain = value.substringAfter('@', "")
        val looksLikeAnAddress =
            at > 0 &&
                value.indexOf('@', at + 1) < 0 &&
                domain.contains('.') &&
                !domain.startsWith('.') &&
                !domain.endsWith('.')
        return if (looksLikeAnAddress) null else PayInFieldError.EmailNotValid
    }

    /** Expects `MM/YY` as [ExpiryValue.format] writes it. */
    private fun expiryError(
        value: String,
        today: ExpiryValue?,
    ): PayInFieldError? {
        val expiry = ExpiryValue.parse(value) ?: return PayInFieldError.ExpiryIncomplete
        if (today != null && expiry.isExpired(today.year, today.month)) return PayInFieldError.ExpiryPast
        return null
    }
}
