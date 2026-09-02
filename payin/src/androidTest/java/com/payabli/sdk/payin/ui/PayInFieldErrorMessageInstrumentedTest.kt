package com.payabli.sdk.payin.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.payabli.sdk.payin.form.PayInField
import com.payabli.sdk.payin.form.PayInFieldError
import com.payabli.sdk.payin.form.PayInMethodType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every message a rule can put under a field, rendered.
 *
 * These are the words a payer reads when their input is refused, and until now no test drew one. The risk is
 * specific rather than general: five of the twelve go through `pluralStringResource`, which takes a count and
 * then formatting arguments, and a resource whose translation carries a different number of placeholders
 * throws at the moment it is rendered. Nothing before this ever rendered them, so the whole set was
 * unexercised in every tier.
 *
 * Asserted as non-empty and distinct rather than against fixed wording: the strings are translatable and this
 * would otherwise be a second copy of the resource file, failing on every copy edit.
 */
@RunWith(AndroidJUnit4::class)
class PayInFieldErrorMessageInstrumentedTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val everyError =
        listOf(
            PayInFieldError.DigitsOnly,
            PayInFieldError.ShorterThan(minimum = 3),
            PayInFieldError.LongerThan(maximum = 9),
            PayInFieldError.TooManyCharacters(maximum = 40),
            PayInFieldError.NotExactly(length = 4),
            PayInFieldError.OutsideRange(minimum = 2, maximum = 8),
            PayInFieldError.CardNumberNotValid,
            PayInFieldError.RoutingNumberNotValid,
            PayInFieldError.EmailNotValid,
            PayInFieldError.ExpiryIncomplete,
            PayInFieldError.ExpiryPast,
            PayInFieldError.NotAccepted,
        )

    @Test
    fun everyErrorRendersAMessage() {
        val rendered = mutableListOf<String>()
        rule.setContent {
            MaterialTheme {
                everyError.forEach { rendered += PayInStrings.error(it) }
            }
        }
        rule.waitForIdle()

        assertEquals("an error stopped producing a message", everyError.size, rendered.size)
        rendered.forEachIndexed { index, message ->
            assertTrue("${everyError[index]} rendered nothing", message.isNotBlank())
        }
    }

    /**
     * The five counted messages say their number, which is the argument a plural resource can silently drop.
     *
     * A resource that takes the count for pluralisation but never formats it reads as a working message and
     * omits the only part a payer needs.
     */
    @Test
    fun aCountedErrorNamesItsNumber() {
        val messages = mutableMapOf<String, String>()
        rule.setContent {
            MaterialTheme {
                messages["shorter"] = PayInStrings.error(PayInFieldError.ShorterThan(minimum = 3))
                messages["longer"] = PayInStrings.error(PayInFieldError.LongerThan(maximum = 9))
                messages["tooMany"] = PayInStrings.error(PayInFieldError.TooManyCharacters(maximum = 40))
                messages["notExactly"] = PayInStrings.error(PayInFieldError.NotExactly(length = 4))
                messages["range"] = PayInStrings.error(PayInFieldError.OutsideRange(minimum = 2, maximum = 8))
            }
        }
        rule.waitForIdle()

        assertTrue(messages.getValue("shorter"), messages.getValue("shorter").contains("3"))
        assertTrue(messages.getValue("longer"), messages.getValue("longer").contains("9"))
        assertTrue(messages.getValue("tooMany"), messages.getValue("tooMany").contains("40"))
        assertTrue(messages.getValue("notExactly"), messages.getValue("notExactly").contains("4"))
        // Both ends, which is the one that takes three arguments and the likeliest to lose one.
        assertTrue(messages.getValue("range"), messages.getValue("range").contains("2"))
        assertTrue(messages.getValue("range"), messages.getValue("range").contains("8"))
    }

    /** A singular and a plural of the same rule differ, which is the whole reason these are plurals. */
    @Test
    fun aCountOfOneReadsDifferentlyFromACountOfMany() {
        var one = ""
        var many = ""
        rule.setContent {
            MaterialTheme {
                one = PayInStrings.error(PayInFieldError.ShorterThan(minimum = 1))
                many = PayInStrings.error(PayInFieldError.ShorterThan(minimum = 5))
            }
        }
        rule.waitForIdle()

        assertTrue(one, one.isNotBlank())
        assertTrue("a count of one read exactly as a count of five: $one", one != many)
    }

    /** Both method names, which name the tab a payer chooses between. */
    @Test
    fun bothMethodsAreNamed() {
        var card = ""
        var bank = ""
        rule.setContent {
            MaterialTheme {
                card = PayInStrings.method(PayInMethodType.Card)
                bank = PayInStrings.method(PayInMethodType.BankAccount)
            }
        }
        rule.waitForIdle()

        assertTrue(card.isNotBlank())
        assertTrue(bank.isNotBlank())
        assertTrue("both methods read the same", card != bank)
    }

    /**
     * The choice fields, whose left half is the value the API is sent.
     *
     * The wording is translatable and the value is not, so the values are asserted exactly: changing one is a
     * wire change dressed as a copy edit.
     */
    @Test
    fun eachChoiceFieldOffersItsApiValues() {
        val choices = mutableMapOf<PayInField, List<Pair<String, String>>>()
        rule.setContent {
            MaterialTheme {
                listOf(PayInField.AccountType, PayInField.AccountHolderType, PayInField.SecCode)
                    .forEach { choices[it] = PayInStrings.choices(it) }
                choices[PayInField.CardNumber] = PayInStrings.choices(PayInField.CardNumber)
            }
        }
        rule.waitForIdle()

        assertEquals(
            listOf("Checking", "Savings"),
            choices.getValue(PayInField.AccountType).map { it.first },
        )
        assertEquals(
            listOf("personal", "business"),
            choices.getValue(PayInField.AccountHolderType).map { it.first },
        )
        assertEquals(
            listOf("web", "ppd", "ccd", "tel"),
            choices.getValue(PayInField.SecCode).map { it.first },
        )
        // A field that offers no choices, which is the branch every other field takes.
        assertEquals(emptyList<Pair<String, String>>(), choices.getValue(PayInField.CardNumber))

        choices.values.flatten().forEach { (value, label) ->
            assertTrue("$value has no wording", label.isNotBlank())
        }
    }
}
