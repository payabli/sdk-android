package com.payabli.sdk.taptopay.attestation.device

import com.payabli.sdk.core.devicekey.DeviceKey
import java.time.Clock
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Base64

/**
 * Produces the proof-of-possession headers `/activate` and `/config` require.
 *
 * **The timestamp is formatted once and both signed and sent.** What is verified is a signature over the
 * string that arrives, so a second formatting of the same instant that differs by one fractional digit fails
 * verification with nothing pointing at the cause. One local value reaches both the signature and the
 * assertion, which makes the divergence unwritable rather than merely avoided.
 *
 * Not in `platform`: nothing here names an Android type. This module's floor is 30, so `java.time` and
 * `java.util.Base64` are both available, which is the same reason the encodings beside it use them.
 *
 * Signing is a round trip into the key store and can take tens of milliseconds on a secure element, so a
 * caller on the main thread wraps it.
 */
internal class DeviceAssertionSigner(
    private val deviceKey: DeviceKey,
    /**
     * Wall-clock, not the monotonic source the throttle uses.
     *
     * The throttle measures an interval against itself, where a clock change must not matter. This value is
     * compared against another party's clock inside a short window, so it has to be the same kind of time.
     */
    private val clock: Clock = Clock.systemUTC(),
) {
    /**
     * An assertion for one call, over a timestamp minted now.
     *
     * **The timestamp bytes go to the signer unhashed.** `SHA256withECDSA` applies the one hash verification
     * expects; pre-hashing here would sign the hash of a hash, which verifies against nothing and is refused.
     *
     * Never cached: an assertion is short-lived, and a reused one is a replay of a proof that was only ever
     * good for the request it was made for.
     */
    fun sign(deviceId: String): DeviceAssertion {
        val timestamp = FORMATTER.format(clock.instant())
        // One call, so the signature and the identity that labels it describe the same key. Taken separately
        // a replacement between them would send a signature that cannot verify against the key that identity
        // selects.
        val signed = deviceKey.sign(timestamp.toByteArray(Charsets.UTF_8))
        return DeviceAssertion(
            assertion = Base64.getEncoder().encodeToString(signed.signature),
            keyId = signed.identity,
            deviceId = deviceId,
            timestamp = timestamp,
        )
    }

    private companion object {
        /**
         * ISO-8601 in UTC with exactly three fractional digits.
         *
         * `ISO_INSTANT` is variable width: it emits no fraction at all when the nanosecond field happens to be
         * zero, which is roughly one call in a thousand and would send a shape other than the agreed one. A
         * pattern would need a zone and an explicit locale, or a device set to a locale with
         * Arabic-Indic numerals renders digits outside the printable range the headers accept. This needs
         * neither, and it truncates rather than rounds.
         */
        val FORMATTER: DateTimeFormatter = DateTimeFormatterBuilder().appendInstant(3).toFormatter()
    }
}
