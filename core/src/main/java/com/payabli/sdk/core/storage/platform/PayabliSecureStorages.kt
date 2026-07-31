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
     * Prefix for the per-store alias. Reverse-DNS to avoid colliding with the **host app's** aliases, not with
     * other apps': the Keystore is scoped per app by UID, so this key lives in the embedding app's namespace.
     * Uninstall takes it, so a reinstall starts clean.
     */
    const val KEY_ALIAS_PREFIX: String = "com.payabli.sdk.core.storage.v1"
    const val DEFAULT_FILE_NAME: String = "payabli-secure-store.json"

    /**
     * One alias per backing file, and no way for a caller to choose otherwise.
     *
     * Within a store every value does share one key, each write still getting its own IV with each blob bound to
     * its entry name. **Across** stores they must not, and a `keyAlias` parameter used to allow it: two stores
     * on different files could hold the same alias, take different locks, and one could delete and recreate the
     * key the other's blobs were sealed under. The survivor's presence check would still pass, against a
     * different key, and its next write would mix. Deriving the alias makes that unreachable rather than merely
     * discouraged, which matters because presence is all a cipher can cheaply prove.
     *
     * Pass `Context.noBackupFilesDir` for [directory]: it keeps ciphertext out of backup and device transfer
     * without the host app adding manifest rules.
     */
    fun create(
        directory: File,
        fileName: String = DEFAULT_FILE_NAME,
        logger: PayabliLogger = PayabliLoggers.of(LogCategory.CORE),
    ): PayabliSecureStorage =
        FileSecureStorage(
            file = File(directory, fileName),
            cipher = KeystoreValueCipher(aliasFor(fileName), logger),
            logger = logger,
        )

    /** The alias [create] will use for [fileName]. Exposed so a test can assert two stores do not share one. */
    fun aliasFor(fileName: String): String = "$KEY_ALIAS_PREFIX.$fileName"
}
