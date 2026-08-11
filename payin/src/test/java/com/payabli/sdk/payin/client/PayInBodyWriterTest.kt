package com.payabli.sdk.payin.client

import com.payabli.sdk.core.network.PayabliJson
import com.payabli.sdk.payin.model.PayInAccountHolderType
import com.payabli.sdk.payin.model.PayInInstrument
import com.payabli.sdk.payin.model.PayInPaymentMethod
import com.payabli.sdk.payin.model.PayInSecCode
import com.payabli.sdk.payin.model.SensitiveDigits
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The writer that keeps a card number out of a `String`.
 *
 * The assertions are over the produced bytes, which is what a caller of this file can see. Whether a `String`
 * was created along the way is not observable from a test.
 */
class PayInBodyWriterTest {
    private fun fragmentText(bytes: ByteArray) = bytes.toString(Charsets.UTF_8)

    private fun parsed(bytes: ByteArray): JsonObject =
        PayabliJson.format.decodeFromString(JsonObject.serializer(), bytes.toString(Charsets.UTF_8))

    @Test
    fun `a card fragment carries the service's own field names and casing`() {
        val fragment = PayInBodyWriter.instrumentFragment(PayInInstrument.Card(testCard()))

        assertEquals(
            """{"method":"card","cardnumber":"$TEST_PAN","cardexp":"12/30",""" +
                """"cardcvv":"$TEST_SECURITY_CODE","cardHolder":"Integration Test","cardzip":"22039"}""",
            fragmentText(fragment),
        )
    }

    @Test
    fun `a bank fragment defaults the authorisation and omits what was not given`() {
        val fragment = PayInBodyWriter.instrumentFragment(PayInInstrument.BankAccount(testAccount()))
        val parsed = parsed(fragment)

        assertEquals(JsonPrimitive("WEB"), parsed["achCode"])
        assertEquals(JsonPrimitive("Checking"), parsed["achAccountType"])
        // Neither was set, so neither is sent: the service's own defaults decide.
        assertNull(parsed["achHolderType"])
        assertNull(parsed["device"])
    }

    @Test
    fun `a bank fragment carries the values it was given`() {
        val data =
            com.payabli.sdk.payin.model.PayInAchData(
                accountNumber = SensitiveDigits.ofString(TEST_ACCOUNT),
                routingNumber = TEST_ROUTING,
                accountType = com.payabli.sdk.payin.model.PayInAccountType.Savings,
                holderName = "A Payer",
                holderType = PayInAccountHolderType.Business,
                secCode = PayInSecCode.Ppd,
                deviceId = "device-1",
            )

        val parsed = parsed(PayInBodyWriter.instrumentFragment(PayInInstrument.BankAccount(data)))

        assertEquals(JsonPrimitive("Savings"), parsed["achAccountType"])
        // Lower case, unlike the account type. The inconsistency is the service's.
        assertEquals(JsonPrimitive("business"), parsed["achHolderType"])
        assertEquals(JsonPrimitive("PPD"), parsed["achCode"])
        assertEquals(JsonPrimitive("device-1"), parsed["device"])
    }

    @Test
    fun `the four other methods carry only what they have`() {
        assertEquals(
            """{"method":"stored","storedMethodId":"tok-1"}""",
            fragmentText(PayInBodyWriter.methodFragment(PayInPaymentMethod.Stored("tok-1"))),
        )
        assertEquals(
            """{"method":"cloud","device":"device-1"}""",
            fragmentText(PayInBodyWriter.methodFragment(PayInPaymentMethod.CloudDevice("device-1"))),
        )
        assertEquals(
            """{"method":"check","checkHolder":"A Payer"}""",
            fragmentText(PayInBodyWriter.methodFragment(PayInPaymentMethod.Check("A Payer"))),
        )
        assertEquals(
            """{"method":"cash"}""",
            fragmentText(PayInBodyWriter.methodFragment(PayInPaymentMethod.Cash)),
        )
    }

    @Test
    fun `a name that needs escaping is escaped by the codec rather than by hand`() {
        val awkward = """A "Payer" \ with	a tab"""
        val fragment = PayInBodyWriter.instrumentFragment(PayInInstrument.Card(testCard(holderName = awkward)))

        // Round-trips, which is the only claim worth making: the escaping is the codec's problem.
        assertEquals(JsonPrimitive(awkward), parsed(fragment)["cardHolder"])
    }

    @Test
    fun `a value that is not digits is a defect rather than a refusal`() {
        // Validation runs before the writer, so reaching it with a letter means this module is wrong, not the
        // caller. It fails loudly and names no value.
        val data = testCard(pan = "4111-1111-1111-1111")

        val failure =
            runCatching { PayInBodyWriter.instrumentFragment(PayInInstrument.Card(data)) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertFalse(failure?.message?.contains("4111") ?: true)
    }

    @Test
    fun `splicing produces one object with the method inside it`() {
        val outer = """{"entryPoint":"e","paymentDetails":{"totalAmount":10.00}}"""

        val body =
            PayInBodyWriter.withPaymentMethod(
                outer,
                PayInBodyWriter.instrumentFragment(PayInInstrument.Card(testCard())),
            )

        val parsed = parsed(body)
        assertEquals(JsonPrimitive("e"), parsed["entryPoint"])
        assertEquals(setOf("entryPoint", "paymentDetails", "paymentMethod"), parsed.keys)
        assertEquals(
            JsonPrimitive(TEST_PAN),
            (parsed["paymentMethod"] as JsonObject)["cardnumber"],
        )
    }

    @Test
    fun `splicing into an empty object needs no separator`() {
        // Every body carries an entry point, so this pins the guard, not a live path.
        val body = PayInBodyWriter.withPaymentMethod("{}", PayInBodyWriter.methodFragment(PayInPaymentMethod.Cash))

        assertEquals("""{"paymentMethod":{"method":"cash"}}""", fragmentText(body))
    }

    @Test
    fun `the fragment is overwritten once it has been spliced`() {
        val fragment = PayInBodyWriter.instrumentFragment(PayInInstrument.Card(testCard()))

        PayInBodyWriter.withPaymentMethod("""{"entryPoint":"e"}""", fragment)

        assertTrue(fragment.all { it == 0.toByte() })
    }

    @Test
    fun `something that is not a JSON object is refused`() {
        listOf("", "[1,2]", """{"unterminated":true""").forEach { outer ->
            val failure =
                runCatching {
                    PayInBodyWriter.withPaymentMethod(outer, PayInBodyWriter.methodFragment(PayInPaymentMethod.Cash))
                }.exceptionOrNull()

            assertTrue(outer, failure is IllegalArgumentException)
        }
    }

    @Test
    fun `a body longer than the initial buffer still comes out intact`() {
        // The buffer grows by copying, and the array it replaces is overwritten. A body over 256 bytes is what
        // exercises that path, and a real one with customer data always is.
        val longName = "A".repeat(60)
        val outer = """{"entryPoint":"${"e".repeat(300)}"}"""

        val body =
            PayInBodyWriter.withPaymentMethod(
                outer,
                PayInBodyWriter.instrumentFragment(PayInInstrument.Card(testCard(holderName = longName))),
            )

        val parsed = parsed(body)
        assertEquals(300, parsed["entryPoint"]?.let { (it as JsonPrimitive).content?.length })
        assertEquals(
            JsonPrimitive(longName),
            (parsed["paymentMethod"] as JsonObject)["cardHolder"],
        )
    }
}
