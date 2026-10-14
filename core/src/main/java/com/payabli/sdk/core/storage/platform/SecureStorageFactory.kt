package com.payabli.sdk.core.storage.platform

import com.payabli.sdk.core.logging.LogCategory
import com.payabli.sdk.core.logging.LoggerRegistry
import com.payabli.sdk.core.logging.SdkLogger
import com.payabli.sdk.core.storage.PayabliSecureStorage
import com.payabli.sdk.core.storage.impl.FileSecureStorage
import com.payabli.sdk.core.storage.impl.StoreIdentity
import kotlinx.coroutines.CoroutineDispatcher
import java.io.File

/**
 * Builds the shipping [PayabliSecureStorage]: one file of per-entry blobs under one Android Keystore key.
 *
 * Here rather than beside [FileSecureStorage] because naming [KeystoreValueCipher] is what makes a file
 * unreachable from a unit test, and this is the only other place that does. Keeping it out leaves the
 * persistence layer testable on the JVM in full.
 */
internal object SecureStorageFactory {
    /**
     * Prefix for the per-store alias. Reverse-DNS to avoid colliding with the **host app's** aliases, not with
     * other apps': the Keystore is scoped per app by UID, so this key lives in the embedding app's namespace.
     * Uninstall takes it, so a reinstall starts clean.
     */
    const val KEY_ALIAS_PREFIX: String = "com.payabli.sdk.core.storage.v1"
    const val DEFAULT_FILE_NAME: String = "payabli-secure-store.json"

    /**
     * One alias per backing file, from the **whole** file's identity, with no way for a caller to choose
     * otherwise: across stores, keys must not be shared, and [StoreIdentity] is the single answer. A store
     * that moves is a new store, and a path that cannot be resolved raises StorageUnavailable.
     *
     * @param directory the directory the store file lives in; `Context.noBackupFilesDir` keeps ciphertext out of backup.
     * @param dispatcher where the store's blocking work runs.
     * @param fileName the store file's name.
     * @param logger where the store's lifecycle is logged.
     * @return the store, its identity resolved once here.
     */
    fun create(
        directory: File,
        dispatcher: CoroutineDispatcher,
        fileName: String = DEFAULT_FILE_NAME,
        logger: SdkLogger = LoggerRegistry.of(LogCategory.CORE),
    ): PayabliSecureStorage = open(directory, dispatcher, fileName, logger).storage

    /**
     * The store together with the identity it was built from, for a caller that needs the identity as well:
     * [create] discards it, and resolving it a second time is the split [StoreIdentity] exists to prevent.
     *
     * @param directory the directory the store file lives in.
     * @param dispatcher where the store's blocking work runs.
     * @param fileName the store file's name.
     * @param logger where the store's lifecycle is logged.
     * @return the store and the identity it was built from, resolved once.
     */
    fun open(
        directory: File,
        dispatcher: CoroutineDispatcher,
        fileName: String = DEFAULT_FILE_NAME,
        logger: SdkLogger = LoggerRegistry.of(LogCategory.CORE),
    ): OpenedStore {
        val file = File(directory, fileName)
        // Resolved once, here, and handed to both. Resolving separately for the alias and inside the store let a
        // path whose canonical target changed between the two calls, a repointed symlink being the plausible way,
        // give the cipher one identity and persistence another.
        val identity = StoreIdentity.of(file)
        return OpenedStore(
            storage =
                FileSecureStorage(
                    file = file,
                    cipher = KeystoreValueCipher(aliasFor(identity), logger),
                    logger = logger,
                    dispatcher = dispatcher,
                    identity = identity,
                ),
            identity = identity,
        )
    }

    /** A store and the one identity every consumer of it must agree on. */
    internal class OpenedStore(
        val storage: PayabliSecureStorage,
        val identity: String,
    )

    /**
     * The alias for an already-resolved identity, which is what [create] uses so nothing resolves twice.
     *
     * @param identity the store's resolved identity.
     * @return the per-store alias, under the [KEY_ALIAS_PREFIX].
     */
    fun aliasFor(identity: String): String = "$KEY_ALIAS_PREFIX.$identity"

    /**
     * The alias [create] will use for [file]. Exposed so a test can assert two stores do not share one.
     *
     * @param file the store's backing file.
     * @return the per-store alias for the file's identity.
     */
    fun aliasFor(file: File): String = aliasFor(StoreIdentity.of(file))
}
