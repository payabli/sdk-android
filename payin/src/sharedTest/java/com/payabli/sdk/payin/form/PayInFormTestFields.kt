package com.payabli.sdk.payin.form

/**
 * The fields a configuration has to carry to be constructible, for a test whose subject is something else.
 *
 * `PayInFormConfiguration` refuses an offered method missing any field its instrument is built from, so a test
 * about sections, copying or rendering starts from these and adds what it is really about.
 *
 * Read from the production set rather than restated, so a field added there reaches every test at once.
 */
internal val CARD_INSTRUMENT_FIELDS: List<PayInField> =
    PayInFieldRules.instrumentFields(PayInMethodType.Card).toList()

internal val BANK_INSTRUMENT_FIELDS: List<PayInField> =
    PayInFieldRules.instrumentFields(PayInMethodType.BankAccount).toList()

/** A card expiry a payer picks from a dialog, seeded so a test can complete the form by typing the rest. */
internal const val TEST_EXPIRY: String = "09/30"
