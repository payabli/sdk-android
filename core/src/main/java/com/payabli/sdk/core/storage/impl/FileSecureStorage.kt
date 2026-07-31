package com.payabli.sdk.core.storage.impl

import com.payabli.sdk.core.logging.PayabliLogger
import com.payabli.sdk.core.logging.warn
import com.payabli.sdk.core.network.impl.RedactedCause
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
 * `PayabliSecureStorages.create` is how production builds one, and it decides the directory.
 */
internal class FileSecureStorage(
    private val file: File,
    private val cipher: ValueCipher,
    private val logger: PayabliLogger,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : PayabliSecureStorage {
    /**
     * Keyed by path, not per instance, because the factory hands out a new object for the same file and
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
                    // Only an empty store may create a key. A write cannot otherwise tell a fresh install from
                    // a lost alias, and creating for the second case puts the new blob beside ciphertext sealed
                    // under the key that is gone, with nothing reporting the loss: each old value would instead
                    // fail alone on some later read as though it were individually corrupt.
                    //
                    // The decision is the caller's because only the caller knows the store is empty, and it is
                    // one cipher operation because a separate "is there a key" question left a window between
                    // asking and encrypting.
                    cipher.ensureKey(mayCreate = current.isEmpty())
                    plaintext = SecretBuffers.toBytes(value)
                    write(current + (key to cipher.encrypt(key, plaintext)))
                } catch (e: SecureStorageException.KeyInvalidated) {
                    // As on the read path: the remaining blobs are unreadable, and leaving them lets a retry
                    // mix a fresh key with stale ciphertext.
                    write(emptyMap())
                    throw e
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
            // RedactedCause, not e: kotlinx.serialization appends the input it could not parse to its own
            // message, so the cause would carry the file's contents into whatever renders the chain.
            val cause = RedactedCause(e)
            // Persisted, not just returned: otherwise the corrupt file survives and every later read
            // reparses it and warns again. Safe from here because write() neither reads nor locks.
            if (runCatching { write(emptyMap()) }.isSuccess) {
                logger.warn(cause) { "secure storage file was unreadable and has been reset" }
            } else {
                logger.warn(cause) { "secure storage file was unreadable and the reset could not be persisted" }
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
            sweepOrphans(parent)
            val temp = File.createTempFile(tempPrefix(), TEMP_SUFFIX, parent)
            try {
                FileOutputStream(temp).use { out ->
                    out.write(Json.encodeToString(SERIALIZER, values).toByteArray(Charsets.UTF_8))
                    out.flush()
                    out.fd.sync()
                }
                if (!temp.renameTo(file)) throw SecureStorageException.StorageUnavailable()
            } finally {
                // A successful rename leaves nothing here, so absence is the ordinary case. Checked rather
                // than ignored because the failure that lands here, a directory that cannot be written, is
                // also the one that stops the cleanup, and what stays behind is ciphertext.
                if (temp.exists() && !temp.delete()) {
                    logger.warn { "a secure storage temporary file could not be removed" }
                }
            }
        } catch (e: IOException) {
            throw SecureStorageException.StorageUnavailable(e)
        } catch (e: SecurityException) {
            throw SecureStorageException.StorageUnavailable(e)
        }
    }

    /**
     * Removes temporary files left by writes that never finished.
     *
     * Nothing else looks for them. Process death between creating the temp and renaming it orphans one
     * permanently, and it holds ciphertext for every entry present at that moment: under the same key alias
     * those blobs still open, so an entry a later [remove] deleted would survive inside it. An orphan is
     * therefore reclaimed here, on the next write, rather than at the moment it is created.
     *
     * **The match is a shape, not a prefix**, because the lock only covers this store's own file. `fileName` is
     * a parameter, so a sibling store on `store.json2` is legitimate, and a prefix match would delete its temp
     * mid-write and fail its rename. It would also reach an unrelated host-app file in a directory the host app
     * owns too. Requiring the delimiter and an all-digit middle means only this store's own temp names match:
     * `store.json2...` fails the prefix, and `store.json.backup.7.tmp` fails the shape.
     *
     * Deleting a match is then safe, because the caller holds this file's lock, the store is single-process, and
     * the sweep runs before this write's own temp exists.
     */
    private fun sweepOrphans(parent: File) {
        val prefix = tempPrefix()
        parent
            .listFiles { candidate -> isOwnTemp(candidate.name, prefix) }
            ?.forEach { orphan ->
                if (orphan.delete()) logger.warn { "discarded an unfinished secure storage write" }
            }
    }

    /** `<fileName>.` so the digits `createTempFile` appends cannot run into another store's name. */
    private fun tempPrefix(): String = file.name + TEMP_DELIMITER

    /**
     * The exact shape [tempPrefix] plus `createTempFile` produces: prefix, digits, suffix.
     *
     * ASCII digits specifically. `Char.isDigit` follows `Character.isDigit`, which is true for Arabic-Indic and
     * Devanagari numerals among others, while `createTempFile` fills the middle from `Long.toUnsignedString` and
     * so only ever emits `0` to `9`. Accepting the wider set would match `store.json.١.tmp`, a name this store
     * cannot produce, and delete it.
     */
    private fun isOwnTemp(
        name: String,
        prefix: String,
    ): Boolean {
        if (!name.startsWith(prefix) || !name.endsWith(TEMP_SUFFIX)) return false
        val middle = name.substring(prefix.length, name.length - TEMP_SUFFIX.length)
        return middle.isNotEmpty() && middle.all { it in '0'..'9' }
    }

    private companion object {
        private const val TEMP_SUFFIX = ".tmp"
        private const val TEMP_DELIMITER = "."

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
    }
}
