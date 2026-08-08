package com.payabli.example.app.preflight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TapToPayPreflightTest {
    private val appId = "com.payabli.example.app"

    /** Matches [healthy]'s digest, so "everything is fine" means fully configured. */
    private val expectedCert = "AB:CD"

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
            .checks(facts, configuredAppId, expectedCert)
            .first { it.title.contains(titleContains, ignoreCase = true) }
            .status

    @Test
    fun `a healthy device passes every check and reports nothing`() {
        val checks = TapToPayPreflight.checks(healthy(), appId, expectedCert)
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
        val checks = TapToPayPreflight.checks(healthy().copy(model = "Galaxy S24"), appId, expectedCert)
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
    fun `NFC switched off does not block the verdict, and does not read as ready either`() {
        // Two verdicts could not say what this device is. Rolled into Ready, the card announced
        // "Ready to take payments" above the reason it could not; rolled into NotAvailable it would
        // send a reader looking for a fault in the app.
        val checks = TapToPayPreflight.checks(healthy().copy(isNfcEnabled = false), appId, expectedCert)
        assertEquals(Readiness.ActionNeeded, readinessFrom(checks))
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
                .checks(healthy(), "com.example.other", expectedCert)
                .first { it.title.contains("App ID") }
        assertTrue(check.detail.contains("com.example.other"))
        assertTrue(check.detail.contains(appId))
    }

    // --- signing certificate ---

    /** By position, because this check's title is one of five and says what it found. */
    private fun appIdCheckWith(
        expectedCertificate: String,
        facts: DeviceFacts = healthy(),
    ): PreflightCheck = TapToPayPreflight.checks(facts, appId, expectedCertificate).last()

    @Test
    fun `with no expected certificate configured the check reports that it did not run`() {
        // Not Pass. problemsIn drops passing checks and the readiness card shows only problems, so
        // Pass put the instruction in a detail nothing rendered and let the verdict read "Ready to
        // take payments" on a build whose signing key had never been compared to anything.
        val check = appIdCheckWith("")
        assertEquals(CheckStatus.Unknown, check.status)
        assertTrue("does not say how to check it", check.detail.contains("payabli.demo.signingCertificate"))
    }

    @Test
    fun `an unconfigured signing key is shown, and does not read as ready`() {
        val checks = TapToPayPreflight.checks(healthy(), appId, expectedCertificate = "")
        assertTrue(problemsIn(checks).any { it.title.contains("Signing key") })
        assertEquals(Readiness.ActionNeeded, readinessFrom(checks))
    }

    @Test
    fun `a build signed by the wrong key fails`() {
        // The reason the setting exists. Every other check passes on such a build, and attestation
        // rejects it much later with nothing on this screen pointing at why.
        assertEquals(CheckStatus.Fail, appIdCheckWith("EF:01").status)
    }

    @Test
    fun `a mismatch names both sides`() {
        val check = appIdCheckWith("EF:01")
        assertTrue(check.detail.contains("EF:01"))
        assertTrue(check.detail.contains("AB:CD"))
    }

    @Test
    fun `the expected certificate matches`() {
        assertEquals(CheckStatus.Pass, appIdCheckWith("AB:CD").status)
    }

    @Test
    fun `punctuation and case in the expected certificate are not the comparison`() {
        // The Play Console, keytool and apksigner each print this differently, and retyping one of
        // them into the setting must not read as a build signed by the wrong key.
        assertEquals(CheckStatus.Pass, appIdCheckWith("abcd").status)
        assertEquals(CheckStatus.Pass, appIdCheckWith("ab:cd").status)
        assertEquals(CheckStatus.Pass, appIdCheckWith(" AB CD ").status)
    }

    @Test
    fun `an unreadable certificate stays unknown even with one configured`() {
        // Nothing observed says the key is wrong, so this is not the mismatch failure.
        assertEquals(
            CheckStatus.Unknown,
            appIdCheckWith("AB:CD", healthy().copy(signingCertificateDigest = null)).status,
        )
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
        val checks = TapToPayPreflight.checks(healthy().copy(signingCertificateDigest = null), appId, expectedCert)
        assertEquals(Readiness.ActionNeeded, readinessFrom(checks))
    }

    @Test
    fun `only a clean sweep reads as ready`() {
        assertEquals(Readiness.Ready, readinessFrom(TapToPayPreflight.checks(healthy(), appId, expectedCert)))
    }

    // --- the rollup ---

    @Test
    fun `one failure among warnings still blocks`() {
        val checks =
            TapToPayPreflight.checks(healthy().copy(isNfcEnabled = false, apiLevel = 29), appId, expectedCert)
        assertEquals(Readiness.NotAvailable, readinessFrom(checks))
    }

    @Test
    fun `problems exclude passing checks but keep warnings and unknowns`() {
        val checks =
            TapToPayPreflight.checks(
                healthy().copy(isNfcEnabled = false, signingCertificateDigest = null),
                appId,
                expectedCert,
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
        TapToPayPreflight.checks(broken, "", expectedCert).forEach { check ->
            assertTrue("${check.title} has no title", check.title.isNotBlank())
            assertTrue("${check.title} has no detail", check.detail.isNotBlank())
        }
    }
}
