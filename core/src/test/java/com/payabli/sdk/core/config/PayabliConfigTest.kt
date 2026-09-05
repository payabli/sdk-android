package com.payabli.sdk.core.config

import com.payabli.sdk.core.model.PayabliErrorCode
import com.payabli.sdk.core.model.PayabliException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

/** The 2.1 configuration surface: environment base URLs, validation, and what `toString` may reveal. */
class PayabliConfigTest {
    // A name and an origin no environment uses, so nothing here reads as a real deployment.
    private val fixtureName = "fixture"
    private val fixtureOrigin = "https://api-fixture.payabli.com"

    private val entry = "SENTINEL-ENTRY-POINT"

    private fun config(
        entryPoint: String = entry,
        environment: PayabliEnvironment = PayabliEnvironment.SANDBOX,
        tokenProvider: PayabliTokenProvider = PayabliTokenProvider { "minted" },
        telemetryEnabled: Boolean = true,
    ) = PayabliConfig(
        entryPoint = entryPoint,
        environment = environment,
        tokenProvider = tokenProvider,
        telemetryEnabled = telemetryEnabled,
    )

    private fun failureFrom(block: () -> Unit): PayabliException {
        val thrown = runCatching { block() }.exceptionOrNull()
        assertTrue("expected a PayabliException, got $thrown", thrown is PayabliException)
        return thrown as PayabliException
    }

    @Test
    fun `each environment resolves its own base URL`() {
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
    fun `the committed environments come first, and a build cannot drop one`() {
        // The list is two committed entries plus whatever the build appended. A build input that could
        // reorder or remove one of the two would be repointing shipped configuration rather than adding to
        // it, and this is what says so on a machine that has added one.
        assertEquals(listOf("sandbox", "production"), PayabliEnvironment.entries.take(2).map { it.name })
    }

    @Test
    fun `an environment is found by the name a setting spells`() {
        // What every caller outside this module does with it: a runner argument, a Gradle property and a
        // reported value are one lowercase spelling, and this is the only thing that resolves one.
        assertEquals(PayabliEnvironment.SANDBOX, PayabliEnvironment.named("sandbox"))
        assertEquals(PayabliEnvironment.PRODUCTION, PayabliEnvironment.named("  PRODUCTION  "))
        assertNull(PayabliEnvironment.named("staging"))
        assertNull(PayabliEnvironment.named(""))
    }

    @Test
    fun `the list an integrator is handed cannot be emptied`() {
        // `List` is read-only to a Kotlin caller and nothing more. The backing object is an ArrayList and
        // @JvmField publishes it as a static field, so without the wrapper a Java caller or a cast could
        // clear it, and every later `named` call would answer nothing.
        @Suppress("UNCHECKED_CAST")
        val asMutable = PayabliEnvironment.entries as MutableList<PayabliEnvironment>

        assertThrows(UnsupportedOperationException::class.java) { asMutable.clear() }
        assertThrows(UnsupportedOperationException::class.java) { asMutable.add(PayabliEnvironment.SANDBOX) }
        assertEquals(PayabliEnvironment.SANDBOX, PayabliEnvironment.named("sandbox"))
    }

    @Test
    fun `an added environment lands after the committed two and changes neither`() {
        // Against a list handed in, because the real one is fixed at build time and is empty here: an
        // assertion on `entries` alone passes on the empty case and never reaches the appending.
        val listed = PayabliEnvironment.listedWith(listOf(fixtureName to fixtureOrigin))

        assertEquals(listOf("sandbox", "production", fixtureName), listed.map { it.name })
        assertEquals(fixtureOrigin, listed.last().baseUrl)
        assertEquals(PayabliEnvironment.SANDBOX, listed[0])
        assertEquals(PayabliEnvironment.PRODUCTION, listed[1])
    }

    @Test
    fun `adding none is the two on their own`() {
        assertEquals(listOf("sandbox", "production"), PayabliEnvironment.listedWith(emptyList()).map { it.name })
    }

    @Test
    fun `an added environment is its own, not one of the two it was added beside`() {
        // Equality is identity, so an addition that happened to name an origin already in the list is still
        // a separate environment. Two names for one origin is a configuration mistake to see, not to merge.
        val listed = PayabliEnvironment.listedWith(listOf(fixtureName to PayabliEnvironment.SANDBOX.baseUrl))

        assertEquals(3, listed.size)
        assertNotEquals(PayabliEnvironment.SANDBOX, listed.last())
    }

    @Test
    fun `environments are distinct, so none silently shares another's origin`() {
        val urls = PayabliEnvironment.entries.map { it.baseUrl }
        assertEquals(urls.size, urls.toSet().size)
    }

    @Test
    fun `a blank entry point is a configuration error`() {
        val failure = failureFrom { config(entryPoint = "") }
        assertEquals(PayabliErrorCode.INVALID_CONFIGURATION, failure.code)
    }

    @Test
    fun `toString reveals the entry point to nobody`() {
        val rendered = config().toString()

        assertFalse("the entry point leaked", rendered.contains(entry))
        assertTrue(rendered.contains("sandbox"))
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
}
