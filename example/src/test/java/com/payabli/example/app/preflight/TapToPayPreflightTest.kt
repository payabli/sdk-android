package com.payabli.example.app.preflight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TapToPayPreflightTest {
    private val appId = "com.payabli.example.app"

    /** A device on which everything is fine, so each test can spoil exactly one thing. */
    private fun healthy() =
        DeviceFacts(
            isEmulator = false,
            model = "Pixel 8",
            apiLevel = 34,
            hasNfcHardware = true,
            isNfcEnabled = true,
            playServicesInstalled = true,
            playStoreInstalled = true,
            packageName = appId,
            signingCertificateDigest = "AB:CD",
        )

    private fun statusOf(
        facts: DeviceFacts,
        titleContains: String,
        configuredAppId: String = appId,
    ): CheckStatus =
        TapToPayPreflight
            .checks(facts, configuredAppId)
            .first { it.title.contains(titleContains, ignoreCase = true) }
            .status

    @Test
    fun `a healthy device passes every check and reports nothing`() {
        val checks = TapToPayPreflight.checks(healthy(), appId)
        assertEquals(5, checks.size)
        assertTrue(checks.all { it.status == CheckStatus.Pass })
        assertEquals(emptyList<PreflightCheck>(), problemsIn(checks))
        assertEquals(Readiness.Ready, readinessFrom(checks))
    }

    // --- host ---

    @Test
    fun `an emulator fails`() {
        assertEquals(CheckStatus.Fail, statusOf(healthy().copy(isEmulator = true), "Emulator"))
    }

    @Test
    fun `the host check names the model, so a reader knows which device answered`() {
        val checks = TapToPayPreflight.checks(healthy().copy(model = "Galaxy S24"), appId)
        assertTrue(checks.any { it.detail.contains("Galaxy S24") })
    }

    // --- integrity ---

    @Test
    fun `no Play services fails`() {
        assertEquals(CheckStatus.Fail, statusOf(healthy().copy(playServicesInstalled = false), "Play services"))
    }

    @Test
    fun `Play services without the Play Store fails`() {
        assertEquals(CheckStatus.Fail, statusOf(healthy().copy(playStoreInstalled = false), "Play Store"))
    }

    // --- NFC ---

    @Test
    fun `no NFC hardware fails`() {
        assertEquals(CheckStatus.Fail, statusOf(healthy().copy(hasNfcHardware = false), "NFC"))
    }

    @Test
    fun `NFC present but switched off warns`() {
        // The distinction the whole status set exists for. One toggle away is not the same as never
        // going to work, and calling it a failure sends a reader hunting a fault in the app.
        assertEquals(CheckStatus.Warn, statusOf(healthy().copy(isNfcEnabled = false), "NFC"))
    }

    @Test
    fun `NFC switched off does not block the verdict`() {
        val checks = TapToPayPreflight.checks(healthy().copy(isNfcEnabled = false), appId)
        assertEquals(Readiness.Ready, readinessFrom(checks))
    }

    // --- API level ---

    @Test
    fun `below the reader floor fails`() {
        assertEquals(CheckStatus.Fail, statusOf(healthy().copy(apiLevel = 29), "Android version"))
    }

    @Test
    fun `exactly the reader floor passes`() {
        assertEquals(
            CheckStatus.Pass,
            statusOf(healthy().copy(apiLevel = TapToPayPreflight.READER_MIN_API), "Android version"),
        )
    }

    // --- app id ---

    @Test
    fun `a blank app id fails`() {
        assertEquals(CheckStatus.Fail, statusOf(healthy(), "App ID", configuredAppId = ""))
    }

    @Test
    fun `an app id that does not match the running package fails`() {
        assertEquals(CheckStatus.Fail, statusOf(healthy(), "App ID", configuredAppId = "com.example.other"))
    }

    @Test
    fun `the mismatch names both sides, so the fix does not need a second look`() {
        val check =
            TapToPayPreflight
                .checks(healthy(), "com.example.other")
                .first { it.title.contains("App ID") }
        assertTrue(check.detail.contains("com.example.other"))
        assertTrue(check.detail.contains(appId))
    }

    @Test
    fun `an unreadable signing certificate is unknown`() {
        // Nothing observed says the signature is wrong, only that this device cannot show it.
        assertEquals(
            CheckStatus.Unknown,
            statusOf(healthy().copy(signingCertificateDigest = null), "Signing certificate"),
        )
    }

    @Test
    fun `an unreadable signing certificate does not block the verdict`() {
        val checks = TapToPayPreflight.checks(healthy().copy(signingCertificateDigest = null), appId)
        assertEquals(Readiness.Ready, readinessFrom(checks))
    }

    // --- the rollup ---

    @Test
    fun `one failure among warnings still blocks`() {
        val checks =
            TapToPayPreflight.checks(
                healthy().copy(isNfcEnabled = false, apiLevel = 29),
                appId,
            )
        assertEquals(Readiness.NotAvailable, readinessFrom(checks))
    }

    @Test
    fun `problems exclude passing checks but keep warnings and unknowns`() {
        val checks =
            TapToPayPreflight.checks(
                healthy().copy(isNfcEnabled = false, signingCertificateDigest = null),
                appId,
            )
        val problems = problemsIn(checks)
        assertEquals(2, problems.size)
        assertTrue(problems.none { it.status == CheckStatus.Pass })
    }

    @Test
    fun `every check says something actionable`() {
        val broken =
            DeviceFacts(
                isEmulator = true,
                model = "sdk_gphone64_arm64",
                apiLevel = 23,
                hasNfcHardware = false,
                isNfcEnabled = false,
                playServicesInstalled = false,
                playStoreInstalled = false,
                packageName = appId,
                signingCertificateDigest = null,
            )
        TapToPayPreflight.checks(broken, "").forEach { check ->
            assertTrue("${check.title} has no title", check.title.isNotBlank())
            assertTrue("${check.title} has no detail", check.detail.isNotBlank())
        }
    }
}
