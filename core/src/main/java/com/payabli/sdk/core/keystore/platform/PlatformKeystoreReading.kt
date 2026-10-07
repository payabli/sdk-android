package com.payabli.sdk.core.keystore.platform

import android.os.Build
import android.security.KeyStoreException
import androidx.annotation.RequiresApi
import com.payabli.sdk.core.keystore.KeystoreFailures
import com.payabli.sdk.core.keystore.KeystoreReading

/** Reads a platform Keystore failure for [KeystoreFailures]. */
internal object PlatformKeystoreReading : (Throwable) -> KeystoreReading? {
    override fun invoke(failure: Throwable): KeystoreReading? =
        KeystoreFailures.readingFor(failure::class.java.name) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) read(failure as KeyStoreException) else null
        }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun read(failure: KeyStoreException): KeystoreReading.Read =
        KeystoreFailures.readingOf(failure.isSystemError, failure.isTransientFailure, failure.numericErrorCode)
}
