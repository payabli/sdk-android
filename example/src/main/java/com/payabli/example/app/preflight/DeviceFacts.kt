package com.payabli.example.app.preflight

/**
 * What the device says about itself at the moment it was asked, as plain data.
 *
 * One reading, not a lasting one. NFC is a Settings toggle, so both screens read the device again on
 * every recheck and this value is replaced; anything holding one of these is holding an answer that
 * has an age.
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
