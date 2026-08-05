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
     * One alias per backing file, from the **whole** file's identity, with no way for a caller to choose otherwise.
     *
     * Within a store every value does share one key, each write still getting its own IV with each blob bound to
     * its entry name. **Across** stores they must not. A `keyAlias` parameter used to allow the collision
     * outright; deriving from `fileName` alone still allowed it, because `directory` is equally part of what
     * identifies a file, so `create(dirA)` and `create(dirB)` on the default name shared one alias while holding
     * separate locks, keyed by path. Either way one store could replace the key the other's ciphertext was sealed
     * under, and the survivor's presence check would pass against a different key. [StoreIdentity] closes it by
     * being the single answer to which store this is, shared with the lock and the temporary-file prefix.
     *
     * **A store that moves is a new store.** The alias follows the canonical path, so relocating the file orphans
     * its key: the old blobs read as `KeyInvalidated`, the store clears, and the next write provisions again.
     * Correct for ciphertext the caller can re-obtain, and the same outcome as an uninstall, but decide the
     * directory with it in mind.
     *
     * Pass `Context.noBackupFilesDir` for [directory]: it keeps ciphertext out of backup and device transfer
     * without the host app adding manifest rules.
     *
     * **This can fail.** The store's identity is resolved here, once, and a path that cannot be resolved raises
     * `SecureStorageException.StorageUnavailable` rather than being answered with a second identity.
     */
    fun create(
        directory: File,
        dispatcher: CoroutineDispatcher,
        fileName: String = DEFAULT_FILE_NAME,
        logger: SdkLogger = LoggerRegistry.of(LogCategory.CORE),
    ): PayabliSecureStorage = open(directory, dispatcher, fileName, logger).storage

    /**
     * The store together with the identity it was built from, for a caller that needs the identity as well.
     *
     * [create] discards it, which is right for a caller that only wants the store, and wrong for one that has
     * to serialise against the same store: resolving it a second time is the split [StoreIdentity] exists to
     * prevent, reappearing at a new composition point. A path whose canonical target changes between the two
     * calls would hand one consumer one identity and the other a different one.
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

    /** The alias for an already-resolved identity, which is what [create] uses so nothing resolves twice. */
    fun aliasFor(identity: String): String = "$KEY_ALIAS_PREFIX.$identity"

    /** The alias [create] will use for [file]. Exposed so a test can assert two stores do not share one. */
    fun aliasFor(file: File): String = aliasFor(StoreIdentity.of(file))
}
