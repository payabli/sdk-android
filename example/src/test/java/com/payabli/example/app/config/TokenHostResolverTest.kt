package com.payabli.example.app.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenHostResolverTest {
    // --- the four tiers, in order ---

    @Test
    fun `launch override wins over everything`() {
        val target = TokenHostResolver.resolve("192.168.1.10", "10.0.0.5", isEmulator = true)
        assertEquals("http://192.168.1.10:8787", target.baseUrl)
        assertEquals(TokenHostSource.LaunchOverride, target.source)
    }

    @Test
    fun `blank launch override falls through to the build setting`() {
        val target = TokenHostResolver.resolve("   ", "10.0.0.5", isEmulator = true)
        assertEquals("http://10.0.0.5:8787", target.baseUrl)
        assertEquals(TokenHostSource.BuildSetting, target.source)
    }

    @Test
    fun `build setting wins over the device kind`() {
        val target = TokenHostResolver.resolve(null, "tokens.local", isEmulator = false)
        assertEquals("http://tokens.local:8787", target.baseUrl)
        assertEquals(TokenHostSource.BuildSetting, target.source)
    }

    @Test
    fun `emulator with nothing configured uses the loopback alias`() {
        val target = TokenHostResolver.resolve(null, "", isEmulator = true)
        assertEquals("http://10.0.2.2:8787", target.baseUrl)
        assertEquals(TokenHostSource.Emulator, target.source)
    }

    @Test
    fun `device with nothing configured uses its own loopback`() {
        val target = TokenHostResolver.resolve(null, "", isEmulator = false)
        assertEquals("http://127.0.0.1:8787", target.baseUrl)
        assertEquals(TokenHostSource.Device, target.source)
    }

    @Test
    fun `a blank build setting is not a configured value`() {
        val target = TokenHostResolver.resolve(null, "   ", isEmulator = true)
        assertEquals(TokenHostSource.Emulator, target.source)
    }

    // --- normalize ---

    @Test
    fun `bare host gets the scheme and the default port`() {
        assertEquals("http://myhost:8787", TokenHostResolver.normalize("myhost"))
    }

    @Test
    fun `explicit port is kept`() {
        assertEquals("http://myhost:9999", TokenHostResolver.normalize("myhost:9999"))
    }

    @Test
    fun `a full http url is taken as written`() {
        assertEquals("http://myhost:1234", TokenHostResolver.normalize("http://myhost:1234"))
    }

    @Test
    fun `https survives, and no port is injected into it`() {
        assertEquals("https://tokens.example.com", TokenHostResolver.normalize("https://tokens.example.com"))
    }

    @Test
    fun `a path is dropped`() {
        assertEquals("http://myhost:8787", TokenHostResolver.normalize("http://myhost:8787/payabli/access-token"))
    }

    @Test
    fun `a trailing slash is dropped`() {
        assertEquals("http://myhost:8787", TokenHostResolver.normalize("http://myhost:8787/"))
    }

    @Test
    fun `surrounding whitespace is ignored`() {
        assertEquals("http://myhost:8787", TokenHostResolver.normalize("  myhost  "))
    }

    @Test
    fun `a port that is not a number falls back to the default`() {
        assertEquals("http://myhost:8787", TokenHostResolver.normalize("myhost:notanumber"))
    }

    @Test
    fun `an empty override does not produce a schemeless url`() {
        assertEquals("http://127.0.0.1:8787", TokenHostResolver.normalize(""))
    }

    @Test
    fun `an ipv6 literal keeps its own colons and still gets a port`() {
        assertEquals("http://[::1]:8787", TokenHostResolver.normalize("[::1]"))
    }

    @Test
    fun `an ipv6 literal with a port keeps both`() {
        assertEquals("http://[::1]:9999", TokenHostResolver.normalize("[::1]:9999"))
    }

    // --- the derived routes ---

    @Test
    fun `the token route is the one that mints`() {
        val target = TokenHostResolver.resolve(null, "", isEmulator = true)
        assertEquals("http://10.0.2.2:8787/payabli/exchange-token", target.accessTokenUrl)
        assertEquals("http://10.0.2.2:8787/health", target.healthUrl)
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
