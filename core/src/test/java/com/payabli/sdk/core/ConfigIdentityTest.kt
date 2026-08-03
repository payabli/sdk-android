package com.payabli.sdk.core

import com.payabli.sdk.core.config.PayabliConfig
import com.payabli.sdk.core.config.PayabliEnvironment
import com.payabli.sdk.core.config.PayabliTokenProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val TOKEN = "an-access-token-that-must-not-leak"

/**
 * What decides whether two `initialize` calls mean the same session.
 *
 * `PayabliSessionTest` proves the consequences through the session. These cover the comparison itself,
 * including the redaction, which nothing reaches through the session because nothing logs this type yet.
 * A redaction with no test is one that stops being true without anything going red.
 */
class ConfigIdentityTest {
    private fun config(
        accessToken: String = TOKEN,
        entryPoint: String = "entry",
        environment: PayabliEnvironment = PayabliEnvironment.SANDBOX,
        telemetryEnabled: Boolean = true,
        tokenProvider: PayabliTokenProvider? = null,
    ) = PayabliSession.ConfigIdentity(
        PayabliConfig(
            accessToken = accessToken,
            entryPoint = entryPoint,
            environment = environment,
            tokenProvider = tokenProvider,
            telemetryEnabled = telemetryEnabled,
        ),
    )

    @Test
    fun `equal values compare equal and agree on a hash`() {
        assertEquals(config(), config())
        assertEquals(config().hashCode(), config().hashCode())
    }

    @Test
    fun `every compared field is actually compared`() {
        // One assertion per field, so a field dropped from the comparison names itself rather than hiding
        // behind whichever other field a single combined case happened to also change.
        assertNotEquals(config(), config(accessToken = "another-token"))
        assertNotEquals(config(), config(entryPoint = "another-entry"))
        assertNotEquals(config(), config(environment = PayabliEnvironment.QA))
        assertNotEquals(config(), config(telemetryEnabled = false))
        assertNotEquals(config(), config(tokenProvider = PayabliTokenProvider { "t" }))
    }

    @Test
    fun `two different provider instances are the same identity`() {
        // The deliberate one. A host writing the callback inline passes a new object every call, and
        // comparing references would make initialize never idempotent for the ordinary way of writing it.
        assertEquals(
            config(tokenProvider = PayabliTokenProvider { "one" }),
            config(tokenProvider = PayabliTokenProvider { "two" }),
        )
    }

    @Test
    fun `it is not equal to something else entirely`() {
        assertFalse(config().equals("not an identity"))
    }

    @Test
    fun `rendering it never renders the access token`() {
        val rendered = config().toString()

        assertFalse("the access token reached toString: $rendered", rendered.contains(TOKEN))
        assertTrue("the entry point is the useful part and should survive", rendered.contains("entry"))
    }
}
