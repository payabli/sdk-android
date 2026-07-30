package com.payabli.sdk.core.storage.platform

import com.payabli.sdk.core.logging.LogCategory
import com.payabli.sdk.core.logging.PayabliLogger
import com.payabli.sdk.core.logging.PayabliLoggers
import com.payabli.sdk.core.storage.PayabliSecureStorage
import com.payabli.sdk.core.storage.impl.FileSecureStorage
import java.io.File

/**
 * Builds the shipping [PayabliSecureStorage]: one file of per-entry blobs under one Android Keystore key.
 *
 * Here rather than beside [FileSecureStorage] because naming [KeystoreValueCipher] is what makes a file
 * unreachable from a unit test, and this is the only other place that does. Keeping it out leaves the
 * persistence layer testable on the JVM in full.
 */
internal object PayabliSecureStorages {
    /**
     * One alias for the whole store. Every value shares the key; each write still gets its own IV, and each
     * blob is bound to its entry name so blobs cannot be swapped.
     *
     * Reverse-DNS to avoid colliding with the **host app's** aliases, not with other apps': the Keystore is
     * scoped per app by UID, so this key lives in the embedding app's namespace. Uninstall takes it, so a
     * reinstall starts clean.
     */
    const val DEFAULT_KEY_ALIAS: String = "com.payabli.sdk.core.storage.v1"
    const val DEFAULT_FILE_NAME: String = "payabli-secure-store.json"

    /**
     * Pass `Context.noBackupFilesDir` for [directory]. It keeps the ciphertext out of backup and device
     * transfer without the host app adding manifest rules.
     */
    fun create(
        directory: File,
        keyAlias: String = DEFAULT_KEY_ALIAS,
        fileName: String = DEFAULT_FILE_NAME,
        logger: PayabliLogger = PayabliLoggers.of(LogCategory.CORE),
    ): PayabliSecureStorage =
        FileSecureStorage(
            file = File(directory, fileName),
            cipher = KeystoreValueCipher(keyAlias, logger),
            logger = logger,
        )
}
