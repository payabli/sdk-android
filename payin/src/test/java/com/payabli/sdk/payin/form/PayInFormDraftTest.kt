package com.payabli.sdk.payin.form

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the draft empties itself, which is the whole of whether a payer keeps what they typed.
 *
 * A composition calls [PayInFormDraft.seed] every time it runs. A changed configuration restyles the form and
 * never restarts it, so a value goes only when the new configuration has no box left for it.
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
    fun aDisplaySettingChangeKeepsWhatWasTypedAndTheChosenMethod() {
        // Masking is a display setting, which says nothing about what the payer has done.
        draft.seed(configuration)
        draft.switchTo(PayInMethodType.BankAccount, configuration)
        draft.enter(PayInField.AccountNumber, "000123456789")

        draft.seed(configuration.copy(formatting = PayInFormatting(masksAccountNumber = false)))

        assertEquals(PayInMethodType.BankAccount, draft.method)
        assertEquals("000123456789", draft.typed[PayInField.AccountNumber])
    }

    @Test
    fun withdrawingTheChosenMethodDropsItsFieldsAndKeepsTheSharedOnes() {
        val both = configuration.copy(cardSections = cardWithBillingEmail(), bankSections = withBillingEmail())
        draft.seed(both)
        draft.switchTo(PayInMethodType.BankAccount, both)
        draft.enter(PayInField.AccountNumber, "000123456789")
        draft.enter(PayInField.BillingEmail, "ada@example.com")

        draft.seed(both.copy(allowedMethods = listOf(PayInMethodType.Card)))

        assertEquals(PayInMethodType.Card, draft.method)
        assertFalse("a bank account number was kept behind a card form", PayInField.AccountNumber in draft.typed)
        assertEquals("ada@example.com", draft.typed[PayInField.BillingEmail])
    }

    @Test
    fun removingAFilledFieldDropsThatFieldAndNothingElse() {
        val withEmail = configuration.copy(cardSections = cardWithBillingEmail())
        draft.seed(withEmail)
        draft.enter(PayInField.CardholderName, "Ada Lovelace")
        draft.enter(PayInField.BillingEmail, "ada@example.com")

        draft.seed(configuration)

        assertFalse(PayInField.BillingEmail in draft.typed)
        assertEquals("Ada Lovelace", draft.typed[PayInField.CardholderName])
    }

    @Test
    fun aRejectionStaysOnABoxTheNewConfigurationStillDraws() {
        // The refused value is still in the box, so lifting the mark would let the same value be sent again.
        draft.seed(configuration)
        draft.enter(PayInField.CardholderName, "Ada Lovelace")
        draft.rejectedFields = mapOf(PayInField.CardholderName to PayInFieldError.NotAccepted)

        draft.seed(configuration.copy(formatting = PayInFormatting(groupsCardNumber = false)))

        assertEquals(PayInFieldError.NotAccepted, draft.rejectedFields[PayInField.CardholderName])
    }

    @Test
    fun aRejectionGoesWithTheBoxTheNewConfigurationRemoves() {
        val withEmail = configuration.copy(cardSections = cardWithBillingEmail())
        draft.seed(withEmail)
        draft.enter(PayInField.BillingEmail, "ada@example.com")
        draft.rejectedFields =
            mapOf(
                PayInField.BillingEmail to PayInFieldError.NotAccepted,
                PayInField.CardholderName to PayInFieldError.NotAccepted,
            )

        draft.seed(configuration)

        assertFalse(PayInField.BillingEmail in draft.rejectedFields)
        assertEquals(PayInFieldError.NotAccepted, draft.rejectedFields[PayInField.CardholderName])
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
    fun clearingTakesEverythingAndStartsTheFormAgainAfterwards() {
        draft.seed(configuration)
        draft.switchTo(PayInMethodType.BankAccount, configuration)
        draft.enter(PayInField.AccountHolder, "Ada Lovelace")
        draft.submissionPending = true

        draft.clear()
        assertTrue(draft.typed.isEmpty())
        assertFalse(draft.submissionPending)

        // The instrument, because it is the only thing here that separates a seed that ran from one that
        // did not. The configuration is the one already seeded from, so a clear that kept what it was
        // started from leaves the payer's bank tab standing; entering a value instead would work either
        // way and prove nothing.
        draft.seed(configuration)
        assertEquals(PayInMethodType.Card, draft.method)
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

    private fun cardWithBillingEmail(): List<PayInFormSection> =
        PayInFormConfiguration.defaultCardSections().map { it.copy(fields = it.fields + PayInField.BillingEmail) }

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
