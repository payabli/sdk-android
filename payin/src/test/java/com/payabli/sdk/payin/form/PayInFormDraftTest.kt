package com.payabli.sdk.payin.form

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.lang.ref.WeakReference

/**
 * When the draft refills itself, which is the whole of whether a payer keeps what they typed.
 *
 * A composition calls [PayInFormDraft.seed] every time it runs, so the question each of these asks is which
 * calls are the same call. Too eager and a rotation empties the form; too lazy and a caller handing over new
 * values is ignored.
 */
class PayInFormDraftTest {
    private val configuration =
        PayInFormConfiguration(
            allowedMethods = listOf(PayInMethodType.Card, PayInMethodType.BankAccount),
            defaultMethod = PayInMethodType.Card,
        )

    private val draft = PayInFormDraft()

    /** Held in a field so each round's array stays reachable until the next one replaces it. */
    private var ballast: ByteArray? = null

    private companion object {
        const val GC_ATTEMPTS = 50
        const val GC_PAUSE_MILLIS = 10L
        const val BALLAST_BYTES = 1 shl 20
        const val SEEDED_PAN = "4111111111111111"
    }

    @Test
    fun seedingTwiceFromTheSameValuesKeepsWhatWasTypedInBetween() {
        // The rotation. The second call is the new composition, and the caller has handed over the same pair.
        draft.seed(configuration, null)
        draft.enter(PayInField.CardholderName, "Ada Lovelace")

        draft.seed(configuration, null)

        assertEquals("Ada Lovelace", draft.typed[PayInField.CardholderName])
    }

    @Test
    fun anEqualConfigurationRebuiltByTheCallerIsNotANewOne() {
        // A host that builds its configuration inline hands over a different instance on every composition.
        draft.seed(configuration, null)
        draft.enter(PayInField.CardholderName, "Ada Lovelace")

        draft.seed(configuration.copy(), null)

        assertEquals("Ada Lovelace", draft.typed[PayInField.CardholderName])
    }

    @Test
    fun newValuesStartTheFormAgain() {
        draft.seed(configuration, null)
        draft.enter(PayInField.CardholderName, "Ada Lovelace")

        draft.seed(configuration, PayInFormValues(PayInMethodType.Card, mapOf(PayInField.CardPostalCode to "22039")))

        assertNull(
            "what the payer typed outlived the values it was replaced by",
            draft.typed[PayInField.CardholderName],
        )
        assertEquals("22039", draft.typed[PayInField.CardPostalCode])
    }

    @Test
    fun aChangedConfigurationStartsTheFormAgain() {
        draft.seed(configuration, null)
        draft.enter(PayInField.CardholderName, "Ada Lovelace")

        draft.seed(configuration.copy(allowedMethods = listOf(PayInMethodType.Card)), null)

        assertNull(draft.typed[PayInField.CardholderName])
    }

    @Test
    fun theSeedDecidesTheInstrumentWhenTheConfigurationOffersIt() {
        draft.seed(configuration, PayInFormValues(PayInMethodType.BankAccount, emptyMap()))

        assertEquals(PayInMethodType.BankAccount, draft.method)
    }

    @Test
    fun aSeededInstrumentTheConfigurationDoesNotOfferIsIgnored() {
        val cardOnly = configuration.copy(allowedMethods = listOf(PayInMethodType.Card))

        cardOnly.let { draft.seed(it, PayInFormValues(PayInMethodType.BankAccount, emptyMap())) }

        assertEquals(PayInMethodType.Card, draft.method)
    }

    @Test
    fun anEmptySeededValueIsNotAValue() {
        // Otherwise a caller seeding a blank field fills the box with nothing and the payer cannot tell it apart
        // from one they typed a space into.
        draft.seed(configuration, PayInFormValues(PayInMethodType.Card, mapOf(PayInField.CardholderName to "")))

        assertFalse(PayInField.CardholderName in draft.typed)
    }

    @Test
    fun typingClearsTheRejectionThatBoxWasCarrying() {
        draft.seed(configuration, null)
        draft.rejectedFields = mapOf(PayInField.CardholderName to PayInFieldError.NotAccepted)

        draft.enter(PayInField.CardholderName, "Grace Hopper")

        assertFalse(PayInField.CardholderName in draft.rejectedFields)
    }

    @Test
    fun switchingInstrumentDropsWhatTheNewOneHasNoBoxFor() {
        draft.seed(configuration, null)
        draft.enter(PayInField.CardNumber, "4111111111111111")
        draft.enter(PayInField.BillingEmail, "ada@example.com")

        draft.switchTo(PayInMethodType.BankAccount, configuration.copy(bankSections = withBillingEmail()))

        assertFalse("a card number was kept behind a bank form", PayInField.CardNumber in draft.typed)
        assertEquals("ada@example.com", draft.typed[PayInField.BillingEmail])
    }

    @Test
    fun anOutcomeEmptiesTheInstrumentAndKeepsTheRest() {
        draft.seed(configuration, null)
        draft.enter(PayInField.CardNumber, "4111111111111111")
        draft.enter(PayInField.CardholderName, "Ada Lovelace")

        draft.clearInstrument()

        assertFalse(PayInField.CardNumber in draft.typed)
        assertEquals("Ada Lovelace", draft.typed[PayInField.CardholderName])
    }

    @Test
    fun clearingTakesEverythingAndSeedsAgainAfterwards() {
        val seed = PayInFormValues(PayInMethodType.Card, mapOf(PayInField.CardPostalCode to "22039"))
        draft.seed(configuration, seed)
        draft.enter(PayInField.CardholderName, "Ada Lovelace")
        draft.submissionPending = true

        draft.clear()
        assertTrue(draft.typed.isEmpty())
        assertFalse(draft.submissionPending)

        // The same pair as before, so a draft that remembered what it was seeded from would stay empty.
        draft.seed(configuration, seed)
        assertEquals("22039", draft.typed[PayInField.CardPostalCode])
        assertNull(draft.typed[PayInField.CardholderName])
    }

    @Test
    fun theCallersValuesAreNotHeldOnceTheyHaveBeenRead() {
        // A PayInFormValues can carry a card number, and the draft outlives the composition, so holding one to
        // compare against would keep the caller's copy for the life of the screen.
        var values: PayInFormValues? =
            PayInFormValues(
                PayInMethodType.Card,
                // Built rather than written as a literal, so the map holds a string the constant pool does not.
                mapOf(PayInField.CardNumber to StringBuilder(SEEDED_PAN).toString()),
            )
        val watched = WeakReference(values)
        draft.seed(configuration, values)

        // Nulled before the loop rather than left to the end of a frame, so nothing but the draft can reach it.
        values = null

        repeat(GC_ATTEMPTS) {
            if (watched.get() == null) return
            System.gc()
            // Real garbage each round, so a collection the runtime is free to skip has a reason to happen. The
            // previous array becomes unreachable as this one replaces it.
            ballast = ByteArray(BALLAST_BYTES)
            Thread.sleep(GC_PAUSE_MILLIS)
        }

        fail("the draft still holds the values it was seeded from")
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
