package com.payabli.sdk.payin.form

/**
 * Which fields a form empties once a submission has an outcome.
 *
 * A value, so a test pins the list and a field added to the form has to be classified before that test passes
 * again.
 *
 * **On a refusal as well as on a success.** The instrument was submitted either way, and a security code is
 * authentication data with no reason to outlive the attempt it authenticated. A payer whose card is declined
 * enters it again.
 */
internal object PayInSensitiveFields {
    /**
     * The instrument, and nothing that identifies the payer.
     *
     * The routing number is here although it identifies a bank and is published: it is half of what charges the
     * account, and the payer entered the pair together. The cardholder name, both postal codes and every billing
     * field stay, so a payer taking a second payment does not type them again.
     */
    val CLEARED_ON_OUTCOME: Set<PayInField> =
        setOf(
            PayInField.CardNumber,
            PayInField.CardExpiration,
            PayInField.CardSecurityCode,
            PayInField.RoutingNumber,
            PayInField.AccountNumber,
        )
}
