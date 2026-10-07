package com.payabli.sdk.core.keystore

import android.os.Build
import android.security.KeyStoreException
import androidx.annotation.RequiresApi

/** What repairs a failed Keystore operation: a retry, a new key, or a fix to this SDK. */
internal enum class KeystoreVerdict { UNAVAILABLE, KEY_GONE, DEFECT }

/** Keystore's own account of a failure, read from the exception it raised. */
internal sealed interface KeystoreReading {
    /** A Keystore failure on a platform version that does not say what kind it is. */
    data object Unreadable : KeystoreReading

    /** Keystore refused the operation until the device is unlocked or Keystore is initialized. */
    data object Locked : KeystoreReading

    data class Read(
        val retryable: Boolean,
        val keyGone: Boolean,
    ) : KeystoreReading
}

/**
 * Classifies a failed cipher or signature operation by Keystore's verdict, never by the JCA exception carrying it.
 *
 * The platform wraps a Keystore failure in whichever checked exception the operation declares, so the same daemon
 * failure arrives as `InvalidKeyException` from `init` and `IllegalBlockSizeException` from `doFinal`. A failure with
 * no Keystore cause is this SDK asking for something the platform does not offer.
 */
internal object KeystoreFailures {
    fun verdictFor(
        cause: Throwable,
        read: (Throwable) -> KeystoreReading?,
    ): KeystoreVerdict {
        val reading =
            generateSequence(cause) { it.cause }
                .take(MAX_CHAIN_DEPTH)
                .firstNotNullOfOrNull(read)
        return when (reading) {
            null -> KeystoreVerdict.DEFECT
            // Retrying is the one answer that cannot discard a working setup.
            KeystoreReading.Unreadable -> KeystoreVerdict.UNAVAILABLE
            KeystoreReading.Locked -> KeystoreVerdict.UNAVAILABLE
            is KeystoreReading.Read ->
                when {
                    reading.retryable -> KeystoreVerdict.UNAVAILABLE
                    reading.keyGone -> KeystoreVerdict.KEY_GONE
                    else -> KeystoreVerdict.DEFECT
                }
        }
    }

    /**
     * The reading for an exception named [className], or null when it carries no Keystore verdict.
     *
     * Matched by name, because `KeyStoreException` is public only from API 33 and neither class can be built off a
     * device. [platform] reads a `KeyStoreException`, and answers null on a platform version that does not say.
     */
    fun readingFor(
        className: String,
        platform: () -> KeystoreReading.Read?,
    ): KeystoreReading? =
        when (className) {
            USER_NOT_AUTHENTICATED -> KeystoreReading.Locked
            KEYSTORE_EXCEPTION -> platform() ?: KeystoreReading.Unreadable
            else -> null
        }

    /** What a `KeyStoreException`'s own flags and code say. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun readingOf(
        systemError: Boolean,
        transient: Boolean,
        errorCode: Int,
    ): KeystoreReading.Read =
        KeystoreReading.Read(
            retryable = systemError || transient,
            keyGone =
                errorCode == KeyStoreException.ERROR_KEY_DOES_NOT_EXIST ||
                    errorCode == KeyStoreException.ERROR_KEY_CORRUPTED,
        )

    /** A cause chain can loop, and `Throwable.cause` only guards against a cause that is itself. */
    private const val MAX_CHAIN_DEPTH = 16

    private const val KEYSTORE_EXCEPTION = "android.security.KeyStoreException"

    /** Raised with no cause for a locked or uninitialized Keystore. */
    private const val USER_NOT_AUTHENTICATED = "android.security.keystore.UserNotAuthenticatedException"
}
