package com.payabli.sdk.core.storage.impl

import com.payabli.sdk.core.logging.LogCategory
import com.payabli.sdk.core.logging.PayabliLogger
import com.payabli.sdk.core.logging.PayabliLoggers
import com.payabli.sdk.core.logging.warn
import com.payabli.sdk.core.storage.PayabliSecureStorage
import com.payabli.sdk.core.storage.SecureStorageException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * [PayabliSecureStorage] over a single file, with each value encrypted by a [ValueCipher].
 *
 * A file rather than DataStore, which would add a dependency, a `Context`, and a single-instance rule. A
 * plain [File] keeps this constructible without any of those, which is what lets the persistence layer be
 * unit-tested on the JVM against a fake cipher.
 *
 * **Give it a directory excluded from backup.** `Context.noBackupFilesDir` is the intended argument, and
 * it keeps the ciphertext out of backup and device transfer without the host app adding manifest rules.
 */
internal class FileSecureStorage(
    private val file: File,
    private val cipher: ValueCipher,
    private val logger: PayabliLogger,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : PayabliSecureStorage {
    /**
     * Keyed by path, not per instance, because `create()` hands out a new object for the same file and
     * reopening one is supported usage. Two instances with private locks both read the old map and
     * overwrite each other, and worse, both generate a first-use key, where the loser's ciphertext becomes
     * unreadable. Single process only: this is not an OS file lock.
     */
    private val mutex: Mutex = lockFor(file)

    override suspend fun get(key: String): CharArray? =
        withContext(dispatcher) {
            mutex.withLock {
                val blob = read()[key] ?: return@withLock null
                var plaintext: ByteArray? = null
                try {
                    plaintext = cipher.decrypt(key, blob)
                    SecretBuffers.toChars(plaintext)
                } catch (e: SecureStorageException.ValueUnreadable) {
                    // One bad entry: drop it, keep the rest.
                    write(read() - key)
                    throw e
                } catch (e: SecureStorageException.KeyInvalidated) {
                    // The key is gone, so every remaining blob is unreadable too. Keeping them would fail
                    // every later read and let the next write mix a fresh key with stale ciphertext.
                    write(emptyMap())
                    throw e
                } finally {
                    SecretBuffers.wipe(plaintext)
                }
            }
        }

    override suspend fun set(
        key: String,
        value: CharArray,
    ) {
        withContext(dispatcher) {
            mutex.withLock {
                // Read before encrypting, so the whole read-modify-write sits inside one critical section
                // and a test can widen the window from the cipher. Encrypting first put the seam outside it.
                val current = read()
                var plaintext: ByteArray? = null
                try {
                    plaintext = SecretBuffers.toBytes(value)
                    write(current + (key to cipher.encrypt(key, plaintext)))
                } finally {
                    SecretBuffers.wipe(plaintext)
                }
            }
        }
    }

    override suspend fun remove(key: String) {
        withContext(dispatcher) {
            mutex.withLock {
                val current = read()
                if (key in current) write(current - key)
            }
        }
    }

    /**
     * A missing file is an empty store. Unparseable content is reset, because refusing to load would make
     * one bad write permanent, and everything here is ciphertext the caller can re-obtain.
     */
    private fun read(): Map<String, String> {
        if (!file.exists()) return emptyMap()
        val text =
            try {
                file.readText()
            } catch (e: IOException) {
                throw SecureStorageException.StorageUnavailable(e)
            }
        return try {
            Json.decodeFromString(SERIALIZER, text)
        } catch (e: IllegalArgumentException) {
            // Persisted, not just returned: otherwise the corrupt file survives and every later read
            // reparses it and warns again. Safe from here because write() neither reads nor locks.
            if (runCatching { write(emptyMap()) }.isSuccess) {
                logger.warn(e) { "secure storage file was unreadable and has been reset" }
            } else {
                logger.warn(e) { "secure storage file was unreadable and the reset could not be persisted" }
            }
            emptyMap()
        }
    }

    /**
     * Write to a sibling temporary file, flush it to disk, then rename over the target.
     *
     * The rename is the atomic step, and nothing may precede it. Deleting the destination first, as an
     * earlier version did, means a crash in that window loses the store outright and the `finally` below
     * then removes the temp copy too. `renameTo` replaces on Android because it maps to `rename(2)`, and
     * the temp file is a sibling so the rename stays within one filesystem.
     *
     * `sync()` covers the file's contents. The parent directory entry is not synced: there is no portable
     * API for that at this module's floor, so a crash between rename and the OS flushing the directory can
     * still lose the newest write. Stated rather than papered over.
     */
    private fun write(values: Map<String, String>) {
        val parent = file.parentFile ?: throw SecureStorageException.StorageUnavailable()
        try {
            if (!parent.exists() && !parent.mkdirs()) throw SecureStorageException.StorageUnavailable()
            val temp = File.createTempFile(file.name, ".tmp", parent)
            try {
                FileOutputStream(temp).use { out ->
                    out.write(Json.encodeToString(SERIALIZER, values).toByteArray(Charsets.UTF_8))
                    out.flush()
                    out.fd.sync()
                }
                if (!temp.renameTo(file)) throw SecureStorageException.StorageUnavailable()
            } finally {
                temp.delete()
            }
        } catch (e: IOException) {
            throw SecureStorageException.StorageUnavailable(e)
        } catch (e: SecurityException) {
            throw SecureStorageException.StorageUnavailable(e)
        }
    }

    internal companion object {
        private val SERIALIZER = MapSerializer(String.serializer(), String.serializer())

        /** One lock per backing file, shared by every instance over it. */
        private val locks = HashMap<String, Mutex>()

        private fun lockFor(file: File): Mutex {
            // Canonical path, so two instances built from different relative paths still share a lock.
            val path = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
            // A plain map under a monitor, not ConcurrentHashMap.computeIfAbsent, which is API 24 against
            // this module's floor of 23. Lint caught that; contention here is one lookup per instance.
            return synchronized(locks) { locks.getOrPut(path) { Mutex() } }
        }

        /**
         * One alias for the whole store. Every value shares the key; each write still gets its own IV, and
         * each blob is bound to its entry name so blobs cannot be swapped.
         *
         * Reverse-DNS to avoid colliding with the **host app's** aliases, not with other apps': the
         * Keystore is scoped per app by UID, so this key lives in the embedding app's namespace. Uninstall
         * takes it, so a reinstall starts clean.
         */
        const val DEFAULT_KEY_ALIAS: String = "com.payabli.sdk.core.storage.v1"
        const val DEFAULT_FILE_NAME: String = "payabli-secure-store.json"

        /** Pass `Context.noBackupFilesDir` for [directory]. */
        internal fun create(
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
}
