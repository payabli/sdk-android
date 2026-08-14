package com.payabli.example.app.demo.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenHostResolverTest {
    /**
     * Stands in for the settings. Unequal to the shipped defaults, so a test of the precedence
     * rules can tell a default that was read from one that was assumed.
     */
    private val defaults = TokenHostDefaults(emulatorHost = "emu.test", deviceHost = "dev.test", port = 8787)

    // --- the four tiers, in order ---

    @Test
    fun `launch override wins over everything`() {
        val target = TokenHostResolver.resolve("192.168.1.10", "10.0.0.5", isEmulator = true, defaults = defaults)
        assertEquals("http://192.168.1.10:8787", target.baseUrl)
        assertEquals(TokenHostSource.LaunchOverride, target.source)
    }

    @Test
    fun `blank launch override falls through to the build setting`() {
        val target = TokenHostResolver.resolve("   ", "10.0.0.5", isEmulator = true, defaults = defaults)
        assertEquals("http://10.0.0.5:8787", target.baseUrl)
        assertEquals(TokenHostSource.BuildSetting, target.source)
    }

    @Test
    fun `build setting wins over the device kind`() {
        val target = TokenHostResolver.resolve(null, "tokens.local", isEmulator = false, defaults = defaults)
        assertEquals("http://tokens.local:8787", target.baseUrl)
        assertEquals(TokenHostSource.BuildSetting, target.source)
    }

    @Test
    fun `emulator with nothing configured uses the configured emulator host`() {
        val target = TokenHostResolver.resolve(null, "", isEmulator = true, defaults = defaults)
        assertEquals("http://emu.test:8787", target.baseUrl)
        assertEquals(TokenHostSource.Emulator, target.source)
    }

    @Test
    fun `device with nothing configured uses the configured device host`() {
        val target = TokenHostResolver.resolve(null, "", isEmulator = false, defaults = defaults)
        assertEquals("http://dev.test:8787", target.baseUrl)
        assertEquals(TokenHostSource.Device, target.source)
    }

    @Test
    fun `a blank build setting is not a configured value`() {
        val target = TokenHostResolver.resolve(null, "   ", isEmulator = true, defaults = defaults)
        assertEquals(TokenHostSource.Emulator, target.source)
    }

    // --- normalize ---

    @Test
    fun `bare host gets the scheme and the default port`() {
        assertEquals("http://myhost:8787", TokenHostResolver.normalize("myhost", defaults))
    }

    @Test
    fun `explicit port is kept`() {
        assertEquals("http://myhost:9999", TokenHostResolver.normalize("myhost:9999", defaults))
    }

    @Test
    fun `a full http url is taken as written`() {
        assertEquals("http://myhost:1234", TokenHostResolver.normalize("http://myhost:1234", defaults))
    }

    @Test
    fun `https survives, and no port is injected into it`() {
        assertEquals("https://tokens.example.com", TokenHostResolver.normalize("https://tokens.example.com", defaults))
    }

    @Test
    fun `a path is dropped`() {
        assertEquals(
            "http://myhost:8787",
            TokenHostResolver.normalize("http://myhost:8787/payabli/access-token", defaults),
        )
    }

    @Test
    fun `a trailing slash is dropped`() {
        assertEquals("http://myhost:8787", TokenHostResolver.normalize("http://myhost:8787/", defaults))
    }

    @Test
    fun `surrounding whitespace is ignored`() {
        assertEquals("http://myhost:8787", TokenHostResolver.normalize("  myhost  ", defaults))
    }

    @Test
    fun `a port that is not a number falls back to the default`() {
        assertEquals("http://myhost:8787", TokenHostResolver.normalize("myhost:notanumber", defaults))
    }

    @Test
    fun `an empty override does not produce a schemeless url`() {
        assertEquals("http://dev.test:8787", TokenHostResolver.normalize("", defaults))
    }

    @Test
    fun `an ipv6 literal keeps its own colons and still gets a port`() {
        assertEquals("http://[::1]:8787", TokenHostResolver.normalize("[::1]", defaults))
    }

    @Test
    fun `an ipv6 literal with a port keeps both`() {
        assertEquals("http://[::1]:9999", TokenHostResolver.normalize("[::1]:9999", defaults))
    }

    // --- what ships when nothing is configured ---

    @Test
    fun `the shipped defaults are the standard local ones`() {
        // The fixture above is not these, so this is the only thing pinning them and a typo in the
        // build file reaches a device without it. Read from the build file: TokenHostDefaults carries
        // what this run resolved, so against BuildConfig this would fail for anyone using the
        // -Ppayabli.demo.* overrides the README documents.
        assertEquals("10.0.2.2", BuildFileDefaults.of("payabli.demo.emulatorTokenHost"))
        assertEquals("127.0.0.1", BuildFileDefaults.of("payabli.demo.deviceTokenHost"))
        assertEquals("8787", BuildFileDefaults.of("payabli.demo.tokenPort"))
    }

    // --- the derived routes ---

    @Test
    fun `the token route is the one that mints`() {
        val target = TokenHostResolver.resolve(null, "", isEmulator = true, defaults = defaults)
        assertEquals("http://emu.test:8787/payabli/exchange-token", target.accessTokenUrl)
        assertEquals("http://emu.test:8787/health", target.healthUrl)
    }

    @Test
    fun `the explanation quotes the address in use, not a written-out one`() {
        // The hosts and the port are settings. A sentence naming 10.0.2.2 describes someone else's
        // build the moment one of them is changed, and this row is what a failed probe is read with.
        val emulator = TokenHostResolver.resolve(null, "", isEmulator = true, defaults = defaults)
        assertTrue(emulator.explanation, emulator.explanation.contains("emu.test:8787"))

        val device = TokenHostResolver.resolve(null, "", isEmulator = false, defaults = defaults)
        assertTrue(device.explanation, device.explanation.contains("dev.test:8787"))
        assertTrue("the adb command lost its port", device.explanation.contains("tcp:8787 tcp:8787"))
    }

    @Test
    fun `a changed port reaches the adb command in the explanation`() {
        val moved = defaults.copy(port = 9191)
        val device = TokenHostResolver.resolve(null, "", isEmulator = false, defaults = moved)
        assertTrue(device.explanation, device.explanation.contains("tcp:9191 tcp:9191"))
    }

    @Test
    fun `every source explains itself`() {
        // assertTrue. Kotlin's `assert` compiles to a no-op unless the JVM is run with assertions
        // enabled, which would make this test pass without checking anything.
        TokenHostSource.entries.forEach { source ->
            val explanation = TokenServerTarget("http://x:1", source).explanation
            assertTrue("$source has no explanation", explanation.isNotBlank())
        }
    }
}
