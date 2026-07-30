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
 * A file rather than DataStore, which SEC-001 permits either of. DataStore would add a dependency, a
 * `Context`, and its own single-instance-per-file rule; a file needs none of those and keeps this class
 * constructible with a plain [File], which is what lets the whole persistence layer be unit-tested on the
 * JVM against a fake cipher.
 *
 * **Give it a directory that is excluded from backup.** `Context.noBackupFilesDir` is the intended
 * argument. That satisfies the blueprint's backup-and-transfer exclusion without asking the host app to
 * add `dataExtractionRules` to its manifest, which would put an SDK concern in the integrator's file.
 */
internal class FileSecureStorage(
    private val file: File,
    private val cipher: ValueCipher,
    private val logger: PayabliLogger,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : PayabliSecureStorage {
    /**
     * Serializes every operation, reads included.
     *
     * The replace below is atomic, so a reader can never see a half-written file, but two writers
     * interleaving read-modify-write would lose one of the two values. Locking the read too keeps the
     * whole class one consistent story rather than two rules to remember.
     */
    private val mutex = Mutex()

    override suspend fun get(key: String): String? =
        withContext(dispatcher) {
            mutex.withLock {
                val blob = read()[key] ?: return@withLock null
                try {
                    cipher.decrypt(blob)
                } catch (e: SecureStorageException.KeyInvalidated) {
                    // Drop the entry, then rethrow. Rethrowing matters because the caller has to
                    // re-authenticate and a null would be indistinguishable from "nothing stored". Dropping it
                    // matters because these bytes can never be read again, so leaving them would make every
                    // future read of this key fail for a reason that is already resolved.
                    write(read() - key)
                    throw e
                }
            }
        }

    override suspend fun set(
        key: String,
        value: String,
    ) {
        withContext(dispatcher) {
            mutex.withLock {
                val blob = cipher.encrypt(value)
                write(read() + (key to blob))
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
     * A missing file is an empty store, not an error.
     *
     * Unparseable content is treated the same way and logged, because the alternative is a store that is
     * permanently unusable after one corrupt write. Nothing readable is lost by starting over: every value
     * in here is ciphertext that the caller is expected to be able to re-obtain, and refusing to start
     * would strand the SDK rather than protect anything.
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
            // Persisted, not only returned. Returning an empty map alone left the corrupt file in place, so a
            // read-only caller reparsed it and warned again on every single get, and the message claimed a
            // reset that had not happened.
            //
            // Safe to write from inside read(): write() neither calls read() nor takes the mutex, and Mutex is
            // not reentrant, so doing this while the lock is held only works because of that second point.
            val persisted = runCatching { write(emptyMap()) }.isSuccess
            if (persisted) {
                logger.warn(e) { "secure storage file was unreadable and has been reset" }
            } else {
                // Kept honest rather than tidy: if the reset could not be written, saying it was would be the
                // same false claim in a different place. The read still succeeds as empty.
                logger.warn(e) { "secure storage file was unreadable and the reset could not be persisted" }
            }
            emptyMap()
        }
    }

    /**
     * Write to a sibling temporary file, flush it to disk, then rename over the target.
     *
     * The rename is what makes this atomic: a reader sees either the whole previous file or the whole new
     * one. `File.renameTo` rather than `Files.move` with `ATOMIC_MOVE`, because `java.nio.file` is API 26
     * and this module's floor is 23. Same directory, so the rename stays within one filesystem, which is
     * the condition for it being atomic at all.
     *
     * The `sync()` matters on its own: without it a crash after the rename can leave the directory entry
     * pointing at a file whose contents never reached the disk.
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
                // renameTo does not replace on every platform, so clear the way first. The window between
                // the two is why the temp file is kept until the rename reports success.
                if (file.exists() && !file.delete()) throw SecureStorageException.StorageUnavailable()
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

        /**
         * One alias for the whole store. Every value shares the key; each write still gets its own IV.
         *
         * Reverse-DNS named to avoid colliding with the **host app's** aliases, not with other apps'. The
         * Keystore is scoped per app, by UID, so cross-app collision is impossible and no package
         * qualification is needed for isolation. What it is needed for is that this key lives inside the
         * embedding app's namespace, alongside whatever aliases that app already uses.
         *
         * Two consequences. Uninstall takes the key with it, so a reinstall starts from a clean store rather
         * than a file it cannot decrypt. And `sharedUserId`, deprecated but not gone, puts two apps in one
         * namespace: both embedding this SDK would then share this alias and therefore this key. That is the
         * one case where "per app" is not literally per package.
         */
        const val DEFAULT_KEY_ALIAS: String = "com.payabli.sdk.core.storage.v1"
        const val DEFAULT_FILE_NAME: String = "payabli-secure-store.json"

        /**
         * The production wiring: an AES-256-GCM Keystore key over a file in [directory].
         *
         * Pass `Context.noBackupFilesDir` for [directory]. See the class comment for why that rather than a
         * manifest rule.
         */
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
