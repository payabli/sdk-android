package com.payabli.sdk.payin.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * The digit fields are set as `String` and never read back. Asserted on the compiled class, because `internal`
 * is a Kotlin rule and the JVM sees a public method unless the member is also synthetic.
 */
class PayInInstrumentSurfaceTest {
    private val types = listOf(PayInCardData::class.java, PayInBankAccountData::class.java)

    @Test
    fun `no method a Java caller can name returns the buffer`() {
        types.forEach { type ->
            val reaching =
                type.methods.filter { !it.isSynthetic && it.returnType == SensitiveDigits::class.java }

            assertEquals("${type.simpleName} hands out the buffer: $reaching", emptyList<Any>(), reaching)
        }
    }

    @Test
    fun `no method a Java caller can name reads or sets a digit field back`() {
        val fields = setOf("CardNumber", "SecurityCode", "AccountNumber")
        types.forEach { type ->
            val reaching =
                type.methods.filter { method ->
                    !method.isSynthetic && fields.any { method.name.removePrefix("get").removePrefix("set") == it }
                }

            assertEquals("${type.simpleName} reads a digit field back: $reaching", emptyList<Any>(), reaching)
        }
    }

    @Test
    fun `no public constructor takes the buffer`() {
        types.forEach { type ->
            val taking =
                type.constructors.filter { constructor ->
                    Modifier.isPublic(constructor.modifiers) &&
                        constructor.parameterTypes.any { it == SensitiveDigits::class.java }
                }

            assertEquals("${type.simpleName} takes the buffer: $taking", emptyList<Any>(), taking)
        }
    }

    @Test
    fun `the public constructors take the digits as strings`() {
        val card = PayInCardData::class.java.constructors.single { Modifier.isPublic(it.modifiers) }
        val bank =
            PayInBankAccountData::class.java.constructors.filter {
                Modifier.isPublic(it.modifiers) && !it.isSynthetic
            }

        assertEquals(String::class.java, card.parameterTypes[0])
        assertEquals(String::class.java, card.parameterTypes[3])
        assertTrue("$bank", bank.isNotEmpty())
        bank.forEach { assertEquals(String::class.java, it.parameterTypes[0]) }
    }

    @Test
    fun `the expiry is a month and a year, after the card number`() {
        val card = PayInCardData::class.java.constructors.single { Modifier.isPublic(it.modifiers) }

        assertEquals(Int::class.javaPrimitiveType, card.parameterTypes[1])
        assertEquals(Int::class.javaPrimitiveType, card.parameterTypes[2])
    }

    @Test
    fun `a card with an expiry the SDK will refuse can still be built`() {
        // The call refuses it, the way it refuses every other bad field.
        val card = PayInCardData("4111111111111111", 13, 30, "123", "Name", "22039")

        assertEquals(13, card.expiryMonth)
        assertEquals(30, card.expiryYear)
    }
}
