package com.payabli.sdk.taptopay.adapters

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The credentials, read into something a reader can be built from. */
class ReaderArmingTest {
    @Test
    fun `every field reaches the reader`() {
        val arming =
            readerCredentials(
                merchantId = "merchant-1",
                terminalId = "terminal-1",
                apiKey = "api-1",
                secretKey = "secret-1",
                ppId = "profile-1",
                hostPort = "reader.example:4443",
            ).toArming()

        assertEquals("merchant-1", arming.merchantId)
        assertEquals("terminal-1", arming.terminalId)
        assertEquals("api-1", arming.apiKey)
        assertEquals("secret-1", arming.secretKey)
        assertEquals("profile-1", arming.ppId)
        assertEquals("reader.example:4443", arming.hostPort)
    }

    @Test
    fun `surrounding space is not part of a credential`() {
        assertEquals("merchant-1", readerCredentials(merchantId = "  merchant-1  ").toArming().merchantId)
    }

    @Test
    fun `a blank required field is refused and the message names it`() {
        val blanks =
            listOf<Pair<String, () -> Unit>>(
                "merchantId" to { readerCredentials(merchantId = "").toArming() },
                "terminalId" to { readerCredentials(terminalId = " ").toArming() },
                "apiKey" to { readerCredentials(apiKey = "").toArming() },
                "secretKey" to { readerCredentials(secretKey = "").toArming() },
                "ppId" to { readerCredentials(ppId = "").toArming() },
                "hostPort" to { readerCredentials(hostPort = "").toArming() },
            )

        for ((field, build) in blanks) {
            val refusal =
                assertThrows(field, CardReaderException.CredentialsUnusable::class.java) { build() }
            assertTrue(
                "the refusal for $field said: ${refusal.message}",
                refusal.message.orEmpty().contains(field),
            )
        }
    }

    @Test
    fun `a refusal never carries the value it refused`() {
        val refusal =
            assertThrows(CardReaderException.CredentialsUnusable::class.java) {
                readerCredentials(environment = "a-live-secret-shaped-value").toArming()
            }

        assertTrue(refusal.message.orEmpty().contains("environment"))
        assertTrue(!refusal.message.orEmpty().contains("a-live-secret-shaped-value"))
    }

    @Test
    fun `the two tier names map to the two environments the reader is pointed at`() {
        assertEquals(ReaderEnvironment.CERT, readerCredentials(environment = "sandbox").toArming().environment)
        assertEquals(
            ReaderEnvironment.PROD,
            readerCredentials(environment = "production").toArming().environment,
        )
    }

    @Test
    fun `an environment named directly is taken as it is`() {
        for (named in ReaderEnvironment.entries) {
            assertEquals(named, readerCredentials(environment = named.name).toArming().environment)
            assertEquals(named, readerCredentials(environment = named.name.lowercase()).toArming().environment)
        }
    }

    @Test
    fun `an environment nobody recognises is refused`() {
        assertThrows(CardReaderException.CredentialsUnusable::class.java) {
            readerCredentials(environment = "staging").toArming()
        }
    }

    @Test
    fun `a currency is read by name and refused when it is not one the reader can authorize in`() {
        assertEquals(ReaderCurrency.USD, readerCredentials(currencyCode = "usd").toArming().currency)
        assertEquals(ReaderCurrency.AUD, readerCredentials(currencyCode = " AUD ").toArming().currency)
        assertThrows(CardReaderException.CredentialsUnusable::class.java) {
            readerCredentials(currencyCode = "EUR").toArming().currency
        }
    }

    @Test
    fun `nothing about the credentials is printed`() {
        val printed = readerCredentials(secretKey = "a-secret-key", apiKey = "an-api-key").toArming().toString()

        assertTrue(printed, !printed.contains("a-secret-key"))
        assertTrue(printed, !printed.contains("an-api-key"))
    }
}
