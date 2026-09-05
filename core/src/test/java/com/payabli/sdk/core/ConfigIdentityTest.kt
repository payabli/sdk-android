package com.payabli.sdk.core

import com.payabli.sdk.core.config.PayabliConfig
import com.payabli.sdk.core.config.PayabliEnvironment
import com.payabli.sdk.core.config.PayabliTokenProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

private const val ENTRY_POINT = "a-merchant-entry-point"

/**
 * What decides whether two `initialize` calls mean the same session.
 *
 * `PayabliSessionTest` proves the consequences through the session. These cover the comparison itself,
 * including the redaction, which nothing reaches through the session because nothing logs this type yet.
 * A redaction with no test is one that stops being true without anything going red.
 */
class ConfigIdentityTest {
    private fun config(
        entryPoint: String = ENTRY_POINT,
        environment: PayabliEnvironment = PayabliEnvironment.SANDBOX,
        telemetryEnabled: Boolean = true,
        tokenProvider: PayabliTokenProvider = PayabliTokenProvider { "t" },
    ) = PayabliSession.ConfigIdentity(
        PayabliConfig(
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
        assertNotEquals(config(), config(entryPoint = "another-entry"))
        assertNotEquals(config(), config(environment = PayabliEnvironment.PRODUCTION))
        assertNotEquals(config(), config(telemetryEnabled = false))
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
    fun `rendering it reveals neither the token nor the entry point`() {
        val rendered = config().toString()

        // The same rule `PayabliConfigTest` holds the configuration to, for the same reason: the entry point
        // names a specific merchant and this string reaches exception messages and crash reports. Copying
        // the fields into another type does not stop that applying, which is how it was missed here.
        assertFalse("the entry point reached toString: $rendered", rendered.contains(ENTRY_POINT))
    }
}
