package com.payabli.sdk.core.keystore

import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.InvalidKeyException
import java.security.NoSuchAlgorithmException
import javax.crypto.IllegalBlockSizeException

/** Stands in for the platform's own Keystore failure, which has no JVM implementation. */
private class PlatformFailure(
    val reading: KeystoreReading,
) : Exception()

private val read: (Throwable) -> KeystoreReading? = { (it as? PlatformFailure)?.reading }

/** The way Keystore reaches a caller: inside the JCA exception the operation throws. */
private fun wrapped(reading: KeystoreReading): Throwable =
    IllegalBlockSizeException().initCause(PlatformFailure(reading))

class KeystoreFailuresTest {
    @Test
    fun `a system error is the key facility not answering`() {
        val cause = wrapped(KeystoreReading.Read(retryable = true, keyMissing = false))

        assertEquals(KeystoreVerdict.UNAVAILABLE, KeystoreFailures.verdictFor(cause, read))
    }

    @Test
    fun `a retryable failure stays unavailable even when it also names a missing key`() {
        val cause = wrapped(KeystoreReading.Read(retryable = true, keyMissing = true))

        assertEquals(KeystoreVerdict.UNAVAILABLE, KeystoreFailures.verdictFor(cause, read))
    }

    @Test
    fun `a key that does not exist is the key gone`() {
        val cause = wrapped(KeystoreReading.Read(retryable = false, keyMissing = true))

        assertEquals(KeystoreVerdict.KEY_GONE, KeystoreFailures.verdictFor(cause, read))
    }

    @Test
    fun `any other permanent refusal is a defect`() {
        val cause = wrapped(KeystoreReading.Read(retryable = false, keyMissing = false))

        assertEquals(KeystoreVerdict.DEFECT, KeystoreFailures.verdictFor(cause, read))
    }

    @Test
    fun `a Keystore failure whose verdict cannot be read is retried`() {
        val cause = InvalidKeyException("Keystore operation failed", PlatformFailure(KeystoreReading.Unreadable))

        assertEquals(KeystoreVerdict.UNAVAILABLE, KeystoreFailures.verdictFor(cause, read))
    }

    @Test
    fun `a failure with no Keystore cause is a defect`() {
        assertEquals(KeystoreVerdict.DEFECT, KeystoreFailures.verdictFor(NoSuchAlgorithmException(), read))
        assertEquals(KeystoreVerdict.DEFECT, KeystoreFailures.verdictFor(InvalidKeyException(), read))
    }

    @Test
    fun `the Keystore cause is found below the first link`() {
        val cause =
            InvalidKeyException(
                "outer",
                IllegalStateException(PlatformFailure(KeystoreReading.Read(retryable = true, keyMissing = false))),
            )

        assertEquals(KeystoreVerdict.UNAVAILABLE, KeystoreFailures.verdictFor(cause, read))
    }

    @Test
    fun `a cause chain that loops ends without a verdict from Keystore`() {
        val first = Exception()
        val second = Exception(first)
        first.initCause(second)

        assertEquals(KeystoreVerdict.DEFECT, KeystoreFailures.verdictFor(first, read))
    }
}
