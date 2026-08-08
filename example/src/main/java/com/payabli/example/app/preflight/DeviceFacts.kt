package com.payabli.example.app.preflight

/**
 * What the device says about itself, read once and passed around as plain data.
 *
 * No `Context` and no `Build` reference reaches [TapToPayPreflight], which is what lets the rules be
 * exercised on a host JVM against every combination the checks care about, including the ones no
 * machine here can produce.
 *
 * @param signingCertificateDigest SHA-256 of this build's signing certificate, or null below API 28
 *   where it cannot be read. Null is "could not check", never "wrong".
 */
data class DeviceFacts(
    val isEmulator: Boolean,
    val model: String,
    val apiLevel: Int,
    val hasNfcHardware: Boolean,
    val isNfcEnabled: Boolean,
    val playServicesInstalled: Boolean,
    val playStoreInstalled: Boolean,
    val packageName: String,
    val signingCertificateDigest: String?,
)
