package com.payabli.sdk.core.config

import com.payabli.sdk.core.model.PayabliErrorCode
import com.payabli.sdk.core.model.PayabliException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

/** The 2.1 configuration surface: environment base URLs, validation, and what `toString` may reveal. */
class PayabliConfigTest {
    private val token = "SENTINEL-ACCESS-TOKEN"
    private val entry = "SENTINEL-ENTRY-POINT"

    private fun config(
        accessToken: String = token,
        entryPoint: String = entry,
        environment: PayabliEnvironment = PayabliEnvironment.SANDBOX,
        tokenProvider: PayabliTokenProvider? = null,
        telemetryEnabled: Boolean = true,
    ) = PayabliConfig(accessToken, entryPoint, environment, tokenProvider, telemetryEnabled)

    private fun failureFrom(block: () -> Unit): PayabliException {
        val thrown = runCatching { block() }.exceptionOrNull()
        assertTrue("expected a PayabliException, got $thrown", thrown is PayabliException)
        return thrown as PayabliException
    }

    @Test
    fun `each environment resolves its own base URL`() {
        assertEquals("https://api-qa.payabli.com", PayabliEnvironment.QA.baseUrl)
        assertEquals("https://api-sandbox.payabli.com", PayabliEnvironment.SANDBOX.baseUrl)
        assertEquals("https://api.payabli.com", PayabliEnvironment.PRODUCTION.baseUrl)
        assertEquals("https://api-sandbox.payabli.com", config().environment.baseUrl)
    }

    @Test
    fun `every environment is an https Payabli origin with no path`() {
        // Guards the shape the transport depends on, and keeps a non-Payabli origin out of shipped
        // configuration: the sibling platform still carries a debug-only tunnel host.
        for (environment in PayabliEnvironment.entries) {
            val uri = URI(environment.baseUrl)
            assertEquals("$environment must be https", "https", uri.scheme)
            assertTrue(
                "$environment must be a payabli.com host, was ${uri.host}",
                uri.host.orEmpty().endsWith(".payabli.com"),
            )
            assertTrue("$environment must carry no path, was '${uri.path}'", uri.path.isNullOrEmpty())
        }
    }

    @Test
    fun `environments are distinct, so none silently shares another's origin`() {
        val urls = PayabliEnvironment.entries.map { it.baseUrl }
        assertEquals(urls.size, urls.toSet().size)
    }

    @Test
    fun `a blank access token is a configuration error, not a late failure`() {
        val failure = failureFrom { config(accessToken = "  ") }
        assertEquals(PayabliErrorCode.INVALID_CONFIGURATION, failure.code)
    }

    @Test
    fun `a blank entry point is a configuration error`() {
        val failure = failureFrom { config(entryPoint = "") }
        assertEquals(PayabliErrorCode.INVALID_CONFIGURATION, failure.code)
    }

    @Test
    fun `toString reveals neither the token nor the entry point`() {
        val rendered = config(tokenProvider = { "unused" }).toString()

        assertFalse("the token leaked", rendered.contains(token))
        assertFalse("the entry point leaked", rendered.contains(entry))
        assertTrue(rendered.contains("SANDBOX"))
        assertTrue(rendered.contains("present"))
    }

    @Test
    fun `toString distinguishes a missing token provider from a present one`() {
        assertTrue(config().toString().contains("absent"))
        assertTrue(config(tokenProvider = { "unused" }).toString().contains("present"))
    }

    @Test
    fun `the token provider is optional and defaults to absent`() {
        assertNull(config().tokenProvider)
        assertTrue(config().telemetryEnabled)
    }

    @Test
    fun `a lambda satisfies the token provider and its value reaches the caller`() =
        runTest {
            // Proves the fun interface is SAM-convertible from a suspending lambda.
            val provider = PayabliTokenProvider { "fresh-token" }
            assertEquals("fresh-token", provider.freshToken())
            assertEquals("fresh-token", config(tokenProvider = provider).tokenProvider?.freshToken())
        }

    @Test
    fun `telemetry can be switched off explicitly`() {
        assertFalse(config(telemetryEnabled = false).telemetryEnabled)
    }

    @Test
    fun `an access token that cannot be a header value is refused at construction`() {
        // CR and LF are header injection; the rest would make setRequestProperty throw from inside the
        // transport, which is the wrong exception type for the wrong reason.
        for (bad in listOf("tok\ren", "tok\nen", "tok en\u0000", "tok\u00e9n", "tok\ten")) {
            val thrown = runCatching { config(accessToken = bad) }.exceptionOrNull()
            assertTrue("$bad should be refused, got $thrown", thrown is PayabliException)
            assertEquals(PayabliErrorCode.INVALID_CONFIGURATION, (thrown as PayabliException).code)
        }
    }

    @Test
    fun `an ordinary bearer credential is accepted`() {
        // Base64url and JWT shapes must keep working; the check must not be so strict it rejects real tokens.
        for (good in listOf("abcDEF123", "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.sig", "a-b_c.d~e=", "tok en")) {
            assertEquals(good, config(accessToken = good).accessToken)
        }
    }
}
