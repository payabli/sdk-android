package com.payabli.example.app.preflight

import android.provider.Settings

/**
 * Whether this device could take a contactless payment, and if not, what to fix.
 *
 * Five checks, each comparing a configured value against the device rather than against itself.
 * Check five reads the running package and this binary's signing certificate, so a configuration
 * that disagrees with the installed app shows up here, before attestation rejects it much later.
 *
 * Pure. Every input arrives in [DeviceFacts]; nothing here touches a framework or the network.
 */
object TapToPayPreflight {
    /** The floor the card-present module builds against. Below this the reader APIs do not exist. */
    const val READER_MIN_API: Int = 30

    /**
     * @param expectedCertificate SHA-256 of the certificate this build should carry, in any
     *   punctuation. Blank when none is configured, and the signing key is then not checked.
     */
    fun checks(
        facts: DeviceFacts,
        configuredAppId: String,
        expectedCertificate: String = "",
    ): List<PreflightCheck> =
        listOf(
            hostCheck(facts),
            integrityCheck(facts),
            nfcCheck(facts),
            apiLevelCheck(facts),
            appIdCheck(facts, configuredAppId, expectedCertificate),
        )

    private fun hostCheck(facts: DeviceFacts): PreflightCheck =
        if (facts.isEmulator) {
            PreflightCheck(
                title = "Emulator",
                // The reader is hardware and no emulator setting substitutes for it. This states a
                // fact about the host; there is nothing here to go and fix.
                detail = "${facts.model}. A contactless payment needs real hardware and cannot be taken here.",
                status = CheckStatus.Fail,
            )
        } else {
            PreflightCheck("Physical device", facts.model, CheckStatus.Pass)
        }

    private fun integrityCheck(facts: DeviceFacts): PreflightCheck =
        when {
            facts.playServicesInstalled && facts.playStoreInstalled ->
                PreflightCheck("Play services", "Available for integrity checks.", CheckStatus.Pass)

            !facts.playServicesInstalled ->
                PreflightCheck(
                    title = "Play services missing",
                    detail = "Device integrity is verified through Play services, which this device does not have.",
                    status = CheckStatus.Fail,
                )

            else ->
                PreflightCheck(
                    title = "Play Store missing",
                    // Play services without the Store is the sideloaded or custom-ROM case: integrity
                    // will answer, but not with a verdict the backend will accept.
                    detail = "Play services are present but the Play Store is not, so integrity cannot be established.",
                    status = CheckStatus.Fail,
                )
        }

    private fun nfcCheck(facts: DeviceFacts): PreflightCheck =
        when {
            !facts.hasNfcHardware ->
                PreflightCheck(
                    title = "No NFC hardware",
                    detail = "This device has no NFC radio, so it cannot read a card.",
                    status = CheckStatus.Fail,
                )

            !facts.isNfcEnabled ->
                PreflightCheck(
                    title = "NFC switched off",
                    // A warning. The hardware is there and one toggle away, which is a different
                    // situation from a device that can never do this.
                    detail = "The hardware is present. Turn NFC on before taking a payment.",
                    status = CheckStatus.Warn,
                    settingsAction = Settings.ACTION_NFC_SETTINGS,
                )

            else -> PreflightCheck("NFC", "Present and switched on.", CheckStatus.Pass)
        }

    private fun apiLevelCheck(facts: DeviceFacts): PreflightCheck =
        if (facts.apiLevel < READER_MIN_API) {
            PreflightCheck(
                title = "Android version too old",
                detail = "The reader needs API $READER_MIN_API or newer; this device is on ${facts.apiLevel}.",
                status = CheckStatus.Fail,
            )
        } else {
            PreflightCheck("Android version", "API ${facts.apiLevel}.", CheckStatus.Pass)
        }

    /** Case and punctuation vary between the Play Console, `keytool` and `apksigner`. */
    private fun String.asDigest(): String = filter { it.isLetterOrDigit() }.uppercase()

    private fun appIdCheck(
        facts: DeviceFacts,
        configuredAppId: String,
        expectedCertificate: String,
    ): PreflightCheck =
        when {
            configuredAppId.isBlank() ->
                PreflightCheck(
                    title = "App ID not set",
                    detail = "Set payabli.demo.appId in example/secrets.properties. Integrity binds a verdict to it.",
                    status = CheckStatus.Fail,
                )

            configuredAppId != facts.packageName ->
                PreflightCheck(
                    title = "App ID does not match this app",
                    detail = "Configured as $configuredAppId, but this app is ${facts.packageName}.",
                    status = CheckStatus.Fail,
                )

            facts.signingCertificateDigest == null ->
                PreflightCheck(
                    title = "Signing certificate unreadable",
                    // Unknown. The app id matched, and nothing observed says the signature is wrong,
                    // only that this device cannot show it.
                    detail =
                        "The app ID matches. Reading the certificate needs API 28 or newer, " +
                            "so it was not checked.",
                    status = CheckStatus.Unknown,
                )

            expectedCertificate.isBlank() ->
                PreflightCheck(
                    title = "Signing key not checked",
                    // Unknown, the same status an unreadable digest gets, because it is the same
                    // situation: this check did not run. A readable digest says some certificate
                    // signed the build, not that it was the right one, and Pass hid the whole thing,
                    // since problemsIn drops passing checks and the card shows only problems. The
                    // instruction below was written into a detail nothing rendered.
                    detail =
                        "The app ID matches. Set payabli.demo.signingCertificate in " +
                            "example/secrets.properties to the digest shown on Setup, and this " +
                            "check compares the running build against it.",
                    status = CheckStatus.Unknown,
                )

            facts.signingCertificateDigest.asDigest() != expectedCertificate.asDigest() ->
                PreflightCheck(
                    title = "Signing certificate does not match",
                    // The case worth catching. Integrity binds its verdict to the app id and the
                    // signing key together, so a build signed by the wrong key passes every other
                    // check here and is rejected by attestation much later, with nothing on this
                    // screen pointing at why.
                    detail =
                        "Expected $expectedCertificate, but this build is signed with " +
                            "${facts.signingCertificateDigest}.",
                    status = CheckStatus.Fail,
                )

            else ->
                PreflightCheck(
                    "App ID and signing certificate",
                    "${facts.packageName}, signed with the expected certificate.",
                    CheckStatus.Pass,
                )
        }
}
