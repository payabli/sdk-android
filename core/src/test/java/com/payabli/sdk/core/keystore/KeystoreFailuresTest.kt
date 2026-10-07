package com.payabli.sdk.core.keystore

import android.security.KeyStoreException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.security.InvalidKeyException
import java.security.NoSuchAlgorithmException
import javax.crypto.IllegalBlockSizeException

/** Stands in for a platform Keystore failure, which has no JVM implementation. */
private class PlatformFailure(
    val reading: KeystoreReading,
) : Exception()

private val read: (Throwable) -> KeystoreReading? = { (it as? PlatformFailure)?.reading }

/** The way Keystore reaches a caller: inside the JCA exception the operation throws. */
private fun wrapped(reading: KeystoreReading): Throwable =
    IllegalBlockSizeException().initCause(PlatformFailure(reading))

private fun verdictOf(
    systemError: Boolean = false,
    transient: Boolean = false,
    errorCode: Int = KeyStoreException.ERROR_INCORRECT_USAGE,
): KeystoreVerdict =
    KeystoreFailures.verdictFor(wrapped(KeystoreFailures.readingOf(systemError, transient, errorCode)), read)

private const val KEYSTORE_EXCEPTION = "android.security.KeyStoreException"
private const val USER_NOT_AUTHENTICATED = "android.security.keystore.UserNotAuthenticatedException"

class KeystoreFailuresTest {
    @Test
    fun `a system error is the key facility not answering`() {
        assertEquals(KeystoreVerdict.UNAVAILABLE, verdictOf(systemError = true))
    }

    @Test
    fun `a transient failure is the key facility not answering`() {
        assertEquals(KeystoreVerdict.UNAVAILABLE, verdictOf(transient = true))
    }

    @Test
    fun `a system error stays unavailable even when its code names a missing key`() {
        assertEquals(
            KeystoreVerdict.UNAVAILABLE,
            verdictOf(systemError = true, errorCode = KeyStoreException.ERROR_KEY_DOES_NOT_EXIST),
        )
    }

    @Test
    fun `a key that does not exist is the key gone`() {
        assertEquals(KeystoreVerdict.KEY_GONE, verdictOf(errorCode = KeyStoreException.ERROR_KEY_DOES_NOT_EXIST))
    }

    @Test
    fun `a corrupted key is the key gone`() {
        assertEquals(KeystoreVerdict.KEY_GONE, verdictOf(errorCode = KeyStoreException.ERROR_KEY_CORRUPTED))
    }

    @Test
    fun `a KeyMint failure that is not a system error is a defect`() {
        assertEquals(KeystoreVerdict.DEFECT, verdictOf(errorCode = KeyStoreException.ERROR_KEYMINT_FAILURE))
    }

    @Test
    fun `incorrect usage is a defect`() {
        assertEquals(KeystoreVerdict.DEFECT, verdictOf(errorCode = KeyStoreException.ERROR_INCORRECT_USAGE))
    }

    @Test
    fun `a Keystore failure whose verdict cannot be read is retried`() {
        val cause = InvalidKeyException("Keystore operation failed", PlatformFailure(KeystoreReading.Unreadable))

        assertEquals(KeystoreVerdict.UNAVAILABLE, KeystoreFailures.verdictFor(cause, read))
    }

    @Test
    fun `a locked Keystore is retried`() {
        assertEquals(
            KeystoreVerdict.UNAVAILABLE,
            KeystoreFailures.verdictFor(PlatformFailure(KeystoreReading.Locked), read),
        )
    }

    @Test
    fun `a failure with no Keystore cause is a defect`() {
        assertEquals(KeystoreVerdict.DEFECT, KeystoreFailures.verdictFor(NoSuchAlgorithmException(), read))
        assertEquals(KeystoreVerdict.DEFECT, KeystoreFailures.verdictFor(InvalidKeyException(), read))
    }

    @Test
    fun `the Keystore cause is found below the first link`() {
        val cause = InvalidKeyException("outer", IllegalStateException(PlatformFailure(KeystoreReading.Unreadable)))

        assertEquals(KeystoreVerdict.UNAVAILABLE, KeystoreFailures.verdictFor(cause, read))
    }

    @Test
    fun `a cause chain that loops ends without a verdict from Keystore`() {
        val first = Exception()
        val second = Exception(first)
        first.initCause(second)

        assertEquals(KeystoreVerdict.DEFECT, KeystoreFailures.verdictFor(first, read))
    }

    @Test
    fun `a Keystore exception is read from what the platform says`() {
        val platform = KeystoreReading.Read(retryable = false, keyGone = true)

        assertEquals(platform, KeystoreFailures.readingFor(KEYSTORE_EXCEPTION) { platform })
    }

    @Test
    fun `a Keystore exception the platform does not describe cannot be read`() {
        assertEquals(
            KeystoreReading.Unreadable,
            KeystoreFailures.readingFor(KEYSTORE_EXCEPTION) { null },
        )
    }

    @Test
    fun `an unauthenticated user is a locked Keystore`() {
        assertEquals(
            KeystoreReading.Locked,
            KeystoreFailures.readingFor(USER_NOT_AUTHENTICATED) { error("not a Keystore exception") },
        )
    }

    @Test
    fun `an expired key and a key not yet valid are not Keystore readings`() {
        listOf(
            "android.security.keystore.KeyExpiredException",
            "android.security.keystore.KeyNotYetValidException",
            "java.security.InvalidKeyException",
        ).forEach { name ->
            assertNull(name, KeystoreFailures.readingFor(name) { error("not a Keystore exception") })
        }
    }
}
