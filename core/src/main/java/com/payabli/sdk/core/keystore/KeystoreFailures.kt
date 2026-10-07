package com.payabli.sdk.core.keystore

/** What repairs a failed Keystore operation: a retry, a new key, or a fix to this SDK. */
internal enum class KeystoreVerdict { UNAVAILABLE, KEY_GONE, DEFECT }

/** Keystore's own account of a failure, read from the exception it raised. */
internal sealed interface KeystoreReading {
    /** A Keystore failure on a platform version that does not say what kind it is. */
    data object Unreadable : KeystoreReading

    data class Read(
        val retryable: Boolean,
        val keyMissing: Boolean,
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
            is KeystoreReading.Read ->
                when {
                    reading.retryable -> KeystoreVerdict.UNAVAILABLE
                    reading.keyMissing -> KeystoreVerdict.KEY_GONE
                    else -> KeystoreVerdict.DEFECT
                }
        }
    }

    /** A cause chain can loop, and `Throwable.cause` only guards against a cause that is itself. */
    private const val MAX_CHAIN_DEPTH = 16
}
