package com.payabli.sdk.core.keystore.platform

import android.os.Build
import android.security.KeyStoreException
import androidx.annotation.RequiresApi
import com.payabli.sdk.core.keystore.KeystoreReading

/**
 * Reads `android.security.KeyStoreException` for [com.payabli.sdk.core.keystore.KeystoreFailures].
 *
 * Matched by name: the class is public from API 33, and below that it exists but says nothing a caller may read.
 */
internal object PlatformKeystoreReading : (Throwable) -> KeystoreReading? {
    override fun invoke(failure: Throwable): KeystoreReading? =
        when {
            failure::class.java.name != KEYSTORE_EXCEPTION -> null
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> KeystoreReading.Unreadable
            else -> read(failure as KeyStoreException)
        }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun read(failure: KeyStoreException): KeystoreReading =
        KeystoreReading.Read(
            retryable = failure.isSystemError || failure.isTransientFailure,
            keyMissing = failure.numericErrorCode == KeyStoreException.ERROR_KEY_DOES_NOT_EXIST,
        )

    private const val KEYSTORE_EXCEPTION = "android.security.KeyStoreException"
}
