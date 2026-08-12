package com.payabli.sdk.payin.form

/**
 * Which fields a form empties once a submission has succeeded.
 *
 * A value, so a test pins the list and a field added to the form has to be classified before that test passes
 * again.
 *
 * **On success only.** The instrument has been used by then. After a failure the values stay: a refusal naming
 * the card number has to be readable beside the number it names, and a decline for insufficient funds would
 * otherwise cost a re-entry of an instrument that was never the problem.
 */
internal object PayInSensitiveFields {
    /**
     * The instrument, and nothing that identifies the payer.
     *
     * The routing number is here although it identifies a bank and is published: it is half of what charges the
     * account, and the payer entered the pair together. The cardholder name, both postal codes and every billing
     * field stay, so a payer taking a second payment does not type them again.
     */
    val CLEARED_ON_SUCCESS: Set<PayInField> =
        setOf(
            PayInField.CardNumber,
            PayInField.CardExpiration,
            PayInField.CardSecurityCode,
            PayInField.RoutingNumber,
            PayInField.AccountNumber,
        )
}
