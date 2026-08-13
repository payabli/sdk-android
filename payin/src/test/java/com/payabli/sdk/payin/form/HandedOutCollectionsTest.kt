package com.payabli.sdk.payin.form

import com.payabli.sdk.payin.model.PayInException
import com.payabli.sdk.payin.model.PayInFailure
import com.payabli.sdk.payin.payment.PayInSubmissionState
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every collection these types hand out refuses to be changed.
 *
 * `@Immutable` tells the Compose compiler that a type's properties cannot change after construction, which is
 * what lets it skip recomposing a composable handed the same instance. Copying what a caller passes in settles
 * only half of that: Kotlin's read-only `Map`, `List` and `Set` are `LinkedHashMap`, `ArrayList` and
 * `LinkedHashSet` at runtime, so the copy handed back out is one a Java caller can clear. That mutation changes
 * what Compose has already read with no state write to observe, which is the one change the annotation promises
 * cannot happen.
 *
 * **Every collection here holds at least two entries, and that is the point rather than a detail.** `toList`,
 * `toMap` and `toSet` answer with an immutable singleton for one entry and an immutable empty for none, so a
 * one-entry fixture refuses mutation whether or not anything wrapped it — the first version of this test was
 * built that way and passed with the wrapping removed.
 *
 * Read through `java.util` rather than Kotlin's interfaces, because the read-only ones have no mutator to call:
 * the hazard is reachable only from Java, and so is the assertion for it.
 */
class HandedOutCollectionsTest {
    @Test
    fun `a form's values cannot be cleared by whoever receives them`() {
        val values =
            PayInFormValues(
                PayInMethodType.Card,
                mapOf(PayInField.CardNumber to "4111", PayInField.CardSecurityCode to "999"),
            )

        assertRefusesClearing(values.values)
    }

    @Test
    fun `labels cannot be cleared by whoever receives them`() {
        val labels =
            PayInFormLabels(
                fieldLabels = mapOf(PayInField.CardNumber to "Card", PayInField.FirstName to "First"),
                fieldPlaceholders = mapOf(PayInField.CardNumber to "1234", PayInField.FirstName to "Ada"),
            )

        assertRefusesClearing(labels.fieldLabels)
        assertRefusesClearing(labels.fieldPlaceholders)
    }

    @Test
    fun `a configuration's collections cannot be cleared by whoever receives them`() {
        val configuration =
            PayInFormConfiguration(
                allowedMethods = listOf(PayInMethodType.Card, PayInMethodType.BankAccount),
                defaultMethod = PayInMethodType.Card,
                cardSections =
                    listOf(
                        PayInFormSection(fields = CARD_INSTRUMENT_FIELDS),
                        PayInFormSection(fields = listOf(PayInField.Amount), style = PayInSectionStyle.Summary),
                    ),
                bankSections =
                    listOf(
                        PayInFormSection(fields = BANK_INSTRUMENT_FIELDS),
                        PayInFormSection(fields = listOf(PayInField.Amount), style = PayInSectionStyle.Summary),
                    ),
                requiredFields = setOf(PayInField.CardNumber, PayInField.FirstName),
                hiddenFieldLabels = setOf(PayInField.CardNumber, PayInField.FirstName),
                summaryValues = mapOf(PayInField.Amount to "1.10", PayInField.ServiceFee to "0.10"),
            )

        assertRefusesClearing(configuration.allowedMethods)
        assertRefusesClearing(configuration.cardSections)
        assertRefusesClearing(configuration.bankSections)
        assertRefusesClearing(configuration.requiredFields)
        assertRefusesClearing(configuration.hiddenFieldLabels)
        assertRefusesClearing(configuration.summaryValues)
        // Derived rather than copied, so this was the one handing back the field itself.
        assertRefusesClearing(configuration.methodsOffered)
        assertRefusesClearing(configuration.cardSections.first().fields)
    }

    @Test
    fun `a refusal's field errors cannot be cleared by whoever receives them`() {
        // The form retains this map for as long as the boxes it marks are on screen, so clearing it would
        // unmark them with nothing recomposing.
        val failed =
            PayInSubmissionState.Failed(
                cause = PayInException.Refused(PayInFailure("D1001", "Refused", null, null, 200)),
                fieldErrors =
                    mapOf(
                        PayInField.CardNumber to PayInFieldError.NotAccepted,
                        PayInField.FirstName to PayInFieldError.NotAccepted,
                    ),
            )

        assertRefusesClearing(failed.fieldErrors)
    }

    private fun assertRefusesClearing(collection: Map<*, *>) {
        assertTrue("a fixture of fewer than two entries proves nothing here", collection.size >= 2)
        val refused = runCatching { (collection as java.util.Map<*, *>).clear() }.exceptionOrNull()
        assertTrue("a caller cleared a map this type handed out", refused is UnsupportedOperationException)
        assertTrue("the map was cleared", collection.size >= 2)
    }

    private fun assertRefusesClearing(collection: Collection<*>) {
        assertTrue("a fixture of fewer than two entries proves nothing here", collection.size >= 2)
        val refused = runCatching { (collection as java.util.Collection<*>).clear() }.exceptionOrNull()
        assertTrue("a caller cleared a collection this type handed out", refused is UnsupportedOperationException)
        assertTrue("the collection was cleared", collection.size >= 2)
    }
}
