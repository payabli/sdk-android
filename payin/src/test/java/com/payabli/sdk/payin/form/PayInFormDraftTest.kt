package com.payabli.sdk.payin.form

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the draft empties itself, which is the whole of whether a payer keeps what they typed.
 *
 * A composition calls [PayInFormDraft.seed] every time it runs, so the question each of these asks is which
 * calls are the same call. Too eager and a rotation empties the form; too lazy and a caller handing over a
 * different form draws the one before it.
 */
class PayInFormDraftTest {
    private val configuration =
        PayInFormConfiguration(
            allowedMethods = listOf(PayInMethodType.Card, PayInMethodType.BankAccount),
            defaultMethod = PayInMethodType.Card,
        )

    private val draft = PayInFormDraft()

    @Test
    fun seedingTwiceWithTheSameConfigurationKeepsWhatWasTypedInBetween() {
        // The rotation. The second call is the new composition, and the caller has handed over the same one.
        draft.seed(configuration)
        draft.enter(PayInField.CardholderName, "Ada Lovelace")

        draft.seed(configuration)

        assertEquals("Ada Lovelace", draft.typed[PayInField.CardholderName])
    }

    @Test
    fun anEqualConfigurationRebuiltByTheCallerIsNotANewOne() {
        // A host that builds its configuration inline hands over a different instance on every composition.
        draft.seed(configuration)
        draft.enter(PayInField.CardholderName, "Ada Lovelace")

        draft.seed(configuration.copy())

        assertEquals("Ada Lovelace", draft.typed[PayInField.CardholderName])
    }

    @Test
    fun aChangedConfigurationStartsTheFormAgain() {
        draft.seed(configuration)
        draft.enter(PayInField.CardholderName, "Ada Lovelace")

        draft.seed(configuration.copy(allowedMethods = listOf(PayInMethodType.Card)))

        assertFalse(PayInField.CardholderName in draft.typed)
    }

    @Test
    fun theConfigurationDecidesTheInstrumentTheFormOpensOn() {
        draft.seed(configuration.copy(defaultMethod = PayInMethodType.BankAccount))

        assertEquals(PayInMethodType.BankAccount, draft.method)
    }

    @Test
    fun typingClearsTheRejectionThatBoxWasCarrying() {
        draft.seed(configuration)
        draft.rejectedFields = mapOf(PayInField.CardholderName to PayInFieldError.NotAccepted)

        draft.enter(PayInField.CardholderName, "Grace Hopper")

        assertFalse(PayInField.CardholderName in draft.rejectedFields)
    }

    @Test
    fun switchingInstrumentDropsWhatTheNewOneHasNoBoxFor() {
        draft.seed(configuration)
        draft.enter(PayInField.CardNumber, "4111111111111111")
        draft.enter(PayInField.BillingEmail, "ada@example.com")

        draft.switchTo(PayInMethodType.BankAccount, configuration.copy(bankSections = withBillingEmail()))

        assertFalse("a card number was kept behind a bank form", PayInField.CardNumber in draft.typed)
        assertEquals("ada@example.com", draft.typed[PayInField.BillingEmail])
    }

    @Test
    fun anOutcomeEmptiesTheInstrumentAndKeepsTheRest() {
        draft.seed(configuration)
        draft.enter(PayInField.CardNumber, "4111111111111111")
        draft.enter(PayInField.CardholderName, "Ada Lovelace")

        draft.clearInstrument()

        assertFalse(PayInField.CardNumber in draft.typed)
        assertEquals("Ada Lovelace", draft.typed[PayInField.CardholderName])
    }

    @Test
    fun clearingTakesEverythingAndTheFormStillWorksAfterwards() {
        draft.seed(configuration)
        draft.enter(PayInField.CardholderName, "Ada Lovelace")
        draft.submissionPending = true

        draft.clear()
        assertTrue(draft.typed.isEmpty())
        assertFalse(draft.submissionPending)

        // The same configuration as before, so a draft that only compared it would treat this as the
        // composition it was already showing and never start the emptied form again.
        draft.seed(configuration)
        draft.enter(PayInField.CardholderName, "Grace Hopper")
        assertEquals("Grace Hopper", draft.typed[PayInField.CardholderName])
    }

    @Test
    fun aClearedDraftStillAnswersWhichInstrumentIsOnScreen() {
        // The clear runs on whichever thread completed the host's scope, and a reader that has already passed
        // seed's check goes straight on to read the instrument. Clearing that under it fails the read.
        draft.seed(configuration)

        draft.clear()

        assertEquals(PayInMethodType.Card, draft.method)
    }

    @Test
    fun readingTheInstrumentBeforeSeedingFails() {
        // A form drawn without seeding first would pick a tab of its own, and nothing would report it.
        val unseeded = PayInFormDraft()

        val thrown = runCatching { unseeded.method }.exceptionOrNull()

        assertTrue("an unseeded draft answered $thrown", thrown is IllegalStateException)
    }

    private fun withBillingEmail(): List<PayInFormSection> =
        listOf(
            PayInFormSection(
                fields =
                    listOf(
                        PayInField.AccountHolder,
                        PayInField.RoutingNumber,
                        PayInField.AccountNumber,
                        PayInField.AccountType,
                        PayInField.BillingEmail,
                    ),
            ),
        )
}
