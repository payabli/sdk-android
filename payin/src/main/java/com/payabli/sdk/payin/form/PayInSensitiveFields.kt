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

/**
 * Of the errors the service sent back about individual fields, the ones whose box [method] still draws.
 *
 * When a submission is refused, the service can say which fields it objected to — a card number it would not
 * accept, an email it could not use. The form keeps those, marks each box, and will not submit again until the
 * payer changes one of them, so the same value cannot be sent twice under the message saying it was rejected.
 *
 * Switching instrument does not clear that. A payer's name, an email and a billing address are asked for by
 * both instruments, so those boxes are still on screen holding the value the service objected to, and the
 * objection still applies to them. An error about a field the new instrument does not draw is dropped, because
 * it would block a form with no box left to change.
 */
internal fun PayInFormConfiguration.rejectedFieldsOnScreen(
    errors: Map<PayInField, PayInFieldError>,
    method: PayInMethodType,
): Map<PayInField, PayInFieldError> = errors.filterKeys { it in inputFieldsFor(method) }
