package com.payabli.example.app.preflight

/**
 * Whether this device could take a contactless payment, and if not, what to fix.
 *
 * Five checks, chosen for what they mean on Android. The iOS demo checks the Simulator, App Attest,
 * the ProximityReader framework, a provisioning entitlement and a `TeamID.bundleId` string; only the
 * first has a counterpart here, and mapping the rest one for one would produce five rows that say
 * nothing about an Android device.
 *
 * The one lesson worth carrying across is the iOS author's: compare the configured value against
 * reality, not against itself. Check five reads the running package and this binary's signing
 * certificate, so a configuration that disagrees with the installed app shows up here, before
 * attestation rejects it much later.
 *
 * Pure. Every input arrives in [DeviceFacts]; nothing here touches a framework or the network.
 */
object TapToPayPreflight {
    /** The floor the card-present module builds against. Below this the reader APIs do not exist. */
    const val READER_MIN_API: Int = 30

    fun checks(
        facts: DeviceFacts,
        configuredAppId: String,
    ): List<PreflightCheck> =
        listOf(
            hostCheck(facts),
            integrityCheck(facts),
            nfcCheck(facts),
            apiLevelCheck(facts),
            appIdCheck(facts, configuredAppId),
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
                    detail = "The hardware is present. Turn NFC on in Settings before taking a payment.",
                    status = CheckStatus.Warn,
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

    private fun appIdCheck(
        facts: DeviceFacts,
        configuredAppId: String,
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

            else -> PreflightCheck("App ID", "${facts.packageName}, signed and matching.", CheckStatus.Pass)
        }
}
