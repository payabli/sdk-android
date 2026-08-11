package com.payabli.sdk.payin.client

import com.payabli.sdk.core.network.PayabliJson
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/** A carrier for the serializer under test, top-level so its generated `serializer()` resolves anywhere. */
@Serializable
internal class AmountBody(
    @Serializable(with = PayInAmountSerializer::class)
    val totalAmount: BigDecimal,
    @Serializable(with = PayInAmountSerializer::class)
    val serviceFee: BigDecimal? = null,
)

/**
 * What an amount looks like on the wire, which is the one thing about it a reviewer cannot check by reading
 * the type.
 */
class PayInAmountSerializerTest {
    private fun encode(
        total: String,
        fee: String? = null,
    ): String =
        PayabliJson.format.encodeToString(
            AmountBody.serializer(),
            AmountBody(BigDecimal(total), fee?.let(::BigDecimal)),
        )

    @Test
    fun `a whole number carries two decimal places`() {
        // The service's own payloads write 10.00, and a Double would print 10.0 here.
        assertEquals("""{"totalAmount":10.00}""", encode("10"))
    }

    @Test
    fun `the amount is a number, not a string`() {
        assertTrue(encode("10").contains(""""totalAmount":10.00"""))
    }

    @Test
    fun `a value no Double can hold survives exactly`() {
        assertEquals("""{"totalAmount":0.10}""", encode("0.10"))
    }

    @Test
    fun `more precision than the wire takes rounds half up`() {
        assertEquals("""{"totalAmount":10.01}""", encode("10.005"))
        assertEquals("""{"totalAmount":10.00}""", encode("10.004"))
    }

    @Test
    fun `a fee of zero is sent rather than dropped`() {
        // The payloads send serviceFee 0.00 explicitly, and an absent fee is not the same statement.
        assertEquals("""{"totalAmount":10.00,"serviceFee":0.00}""", encode("10", "0"))
    }

    @Test
    fun `an absent fee stays absent`() {
        assertEquals("""{"totalAmount":10.00}""", encode("10"))
    }

    @Test
    fun `a number and a quoted number both decode`() {
        listOf("""{"totalAmount":10.00}""", """{"totalAmount":"10.00"}""").forEach { body ->
            val decoded = PayabliJson.format.decodeFromString(AmountBody.serializer(), body)
            assertEquals(BigDecimal("10.00"), decoded.totalAmount)
        }
    }

    @Test
    fun `an amount that is not a number fails the decode`() {
        listOf(
            """{"totalAmount":"ten"}""",
            """{"totalAmount":true}""",
            """{"totalAmount":{"value":10}}""",
            """{"totalAmount":[10]}""",
        ).forEach { body ->
            val failed =
                runCatching { PayabliJson.format.decodeFromString(AmountBody.serializer(), body) }.isFailure
            assertTrue(body, failed)
        }
    }

    @Test
    fun `the decode failure names no amount`() {
        // Separators, so this fails to parse. A bare "4111111111111111" is a valid quoted number and decodes,
        // which is the tolerance the test above asserts.
        val body = """{"totalAmount":"4111-1111-1111-1111"}"""

        val failure =
            runCatching<AmountBody> {
                PayabliJson.format.decodeFromString(AmountBody.serializer(), body)
            }.exceptionOrNull()

        assertNotNull("the decode was expected to fail", failure)
        assertTrue(failure?.message?.contains("4111") == false)
    }
}
