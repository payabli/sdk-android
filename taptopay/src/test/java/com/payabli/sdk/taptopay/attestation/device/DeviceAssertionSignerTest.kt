package com.payabli.sdk.taptopay.attestation.device

import com.payabli.sdk.core.devicekey.DeviceKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

private const val DEVICE_ID = "device-id-value"
private const val KEY_ID = "com.payabli.sdk.core.devicekey.v1.0123456789abcdef0123456789abcdef"

/**
 * The four headers `/activate` is verified by, and the one property their verification rests on.
 *
 * Against a keypair generated on this JVM, because signing behaves the same wherever the key came from. What
 * needs a device is obtaining the key, which is asserted on one.
 */
class DeviceAssertionSignerTest {
    private val keyPair: KeyPair =
        KeyPairGenerator.getInstance("EC").run {
            initialize(256)
            generateKeyPair()
        }

    private val key = FakeDeviceKey(keyPair)

    private val defaultLocale: Locale = Locale.getDefault()

    @After
    fun restoreLocale() {
        Locale.setDefault(defaultLocale)
    }

    private fun signerAt(
        epochSecond: Long,
        nanos: Long = 0,
    ) = DeviceAssertionSigner(key, Clock.fixed(Instant.ofEpochSecond(epochSecond, nanos), ZoneOffset.UTC))

    private fun verifies(
        assertion: DeviceAssertion,
        over: ByteArray,
    ): Boolean =
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(keyPair.public)
            update(over)
            verify(Base64.getDecoder().decode(assertion.assertion))
        }

    private fun sha256(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())

    @Test
    fun `the timestamp carries exactly three fractional digits`() {
        // 2026-08-05T12:00:00Z, nanos zero. ISO_INSTANT would drop the fraction entirely here, which is the
        // shape the server was not told to expect.
        assertEquals("2026-08-05T12:00:00.000Z", signerAt(1785931200).sign(DEVICE_ID).timestamp)
    }

    @Test
    fun `sub-millisecond precision is truncated, not rounded`() {
        // Rounding would carry into the next second at the boundary and produce a timestamp for an instant
        // that never happened.
        assertEquals("2026-08-05T12:00:00.999Z", signerAt(1785931200, 999_999_999).sign(DEVICE_ID).timestamp)
    }

    @Test
    fun `the signature is taken over the exact timestamp the assertion carries`() {
        val assertion = signerAt(1785931200).sign(DEVICE_ID)

        // The load-bearing one. The server re-derives the signed bytes from the string it received, so a
        // second read of the clock or a second format would verify here and fail on the server, reported as
        // an assertion failure with nothing naming the timestamp.
        assertTrue(
            "the signature does not verify over the timestamp that was sent",
            verifies(assertion, sha256(assertion.timestamp)),
        )
    }

    @Test
    fun `the signed bytes are the digest of the timestamp, not the timestamp`() {
        val assertion = signerAt(1785931200).sign(DEVICE_ID)

        // The digest goes to the signer, which hashes it again. Matching the sibling platform, which hands the
        // same digest to an attestation API that hashes it in turn. Signing the timestamp directly verifies
        // just as cleanly on this side and is refused by the server.
        assertEquals(sha256(assertion.timestamp).toList(), key.signed.single().toList())
        assertNotEquals(assertion.timestamp.toByteArray().toList(), key.signed.single().toList())
    }

    @Test
    fun `the clock is read once per assertion`() {
        val counting = CountingClock(Instant.ofEpochSecond(1785931200))

        DeviceAssertionSigner(key, counting).sign(DEVICE_ID)

        // Two reads would give two instants, and the second format could differ in its last digit.
        assertEquals(1, counting.reads.size)
    }

    @Test
    fun `the key's own identifier and the caller's device travel with it`() {
        val assertion = signerAt(1785931200).sign(DEVICE_ID)

        assertEquals(KEY_ID, assertion.keyId)
        assertEquals(DEVICE_ID, assertion.deviceId)
    }

    @Test
    fun `two assertions from one clock are each signed and each verify`() {
        val signer = signerAt(1785931200)

        val first = signer.sign(DEVICE_ID)
        val second = signer.sign(DEVICE_ID)

        // One signature per call, which is what the 120-second window needs and what a cached assertion would
        // break. Asserted on the key the signer drove, not on the two signatures differing: whether one key
        // returns two different signatures over identical input is the provider's nonce strategy, and a
        // deterministic ECDSA provider would fail this while the signer stayed correct.
        assertEquals(2, key.signed.size)
        assertTrue(verifies(first, sha256(first.timestamp)))
        assertTrue(verifies(second, sha256(second.timestamp)))
    }

    @Test
    fun `a locale with its own digits does not reach the timestamp`() {
        // A pattern-based formatter renders Arabic-Indic digits under this default, which are outside the
        // printable range the headers accept and would be refused at construction instead of formatted right.
        Locale.setDefault(Locale.forLanguageTag("ar-EG-u-nu-arab"))

        assertEquals("2026-08-05T12:00:00.000Z", signerAt(1785931200).sign(DEVICE_ID).timestamp)
    }
}

/** A device key over a JVM keypair, recording what it was asked to sign. */
private class FakeDeviceKey(
    private val keyPair: KeyPair,
) : DeviceKey {
    val signed: MutableList<ByteArray> = CopyOnWriteArrayList()

    override val keyId: String = KEY_ID

    override fun publicKeyPoint(): ByteArray = throw UnsupportedOperationException("not asked for here")

    override fun sign(payload: ByteArray): ByteArray {
        signed += payload
        return Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private)
            update(payload)
            sign()
        }
    }
}

/** Counts reads, so "the clock is read once" is asserted rather than assumed. */
private class CountingClock(
    private val instant: Instant,
) : Clock() {
    val reads: MutableList<Instant> = CopyOnWriteArrayList()

    override fun getZone(): ZoneOffset = ZoneOffset.UTC

    override fun withZone(zone: java.time.ZoneId): Clock = this

    override fun instant(): Instant = instant.also { reads += it }
}
