package com.payabli.sdk.core.storage.impl

import com.payabli.sdk.core.logging.SdkLogger
import com.payabli.sdk.core.logging.warn
import com.payabli.sdk.core.network.impl.RedactedCause
import com.payabli.sdk.core.storage.PayabliSecureStorage
import com.payabli.sdk.core.storage.SecureStorageException
import com.payabli.sdk.core.storage.requireRepresentableKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
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
    private val logger: SdkLogger,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    /**
     * The store's resolved identity, injected so one composition resolves it once.
     *
     * The default keeps direct construction working and self-consistent. What it cannot do is make the *factory*
     * consistent: `create` needs the same value for the cipher's alias and for this store, and computing it in both
     * places let a path whose canonical target changed between the calls give the alias one identity and persistence
     * another. That is the split the single-identity work exists to prevent, surviving at the composition point.
     */
    private val identity: String = StoreIdentity.of(file),
) : PayabliSecureStorage {
    /**
     * Keyed by path, not per instance, because the factory hands out a new object for the same file and
     * reopening one is supported usage. Two instances with private locks both read the old map and
     * overwrite each other, and worse, both generate a first-use key, where the loser's ciphertext becomes
     * unreadable. Single process only: this is not an OS file lock.
     */
    private val mutex: Mutex = lockFor(identity)

    override suspend fun get(key: String): ByteArray? =
        withContext(dispatcher) {
            requireRepresentableKey(key)
            mutex.withLock {
                val blob = read()[key] ?: return@withLock null
                try {
                    // Straight out of the cipher, which already returns a fresh array. Nothing is interpreted,
                    // so there is no intermediate to wipe either: the array returned is the caller's.
                    cipher.decrypt(key, blob)
                } catch (e: SecureStorageException.ValueUnreadable) {
                    // One bad entry: drop it, keep the rest.
                    write(read() - key)
                    throw e
                } catch (e: SecureStorageException.KeyInvalidated) {
                    // The key is gone, so every remaining blob is unreadable too. Keeping them would fail
                    // every later read and let the next write mix a fresh key with stale ciphertext.
                    write(emptyMap())
                    throw e
                }
            }
        }

    override suspend fun set(
        key: String,
        value: ByteArray,
    ) {
        withContext(dispatcher) {
            requireRepresentableKey(key)
            mutex.withLock {
                // Read before encrypting, so the whole read-modify-write sits inside one critical section
                // and a test can widen the window from the cipher. Encrypting first put the seam outside it.
                val current = read()
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
                    // The caller's array goes straight to the cipher, which must not retain it. Nothing is
                    // copied or converted, so there is nothing of ours holding the plaintext afterwards.
                    write(current + (key to cipher.encrypt(key, value)))
                } catch (e: SecureStorageException.KeyInvalidated) {
                    // As on the read path: the remaining blobs are unreadable, and leaving them lets a retry
                    // mix a fresh key with stale ciphertext.
                    write(emptyMap())
                    throw e
                }
            }
        }
    }

    override suspend fun remove(key: String) {
        withContext(dispatcher) {
            requireRepresentableKey(key)
            mutex.withLock {
                val current = read()
                // Sweeping on the absent-key path too, because deletion is what remove promises. A set
                // interrupted between the flush and the rename leaves an orphan holding ciphertext for an entry
                // that never reached the store file, so a later remove finds no map entry, would skip the write,
                // and would report success while that blob is still on disk and still decryptable.
                if (key in current) write(current - key) else sweepOrphans()
            }
        }
    }

    /**
     * A missing file is an empty store. Unparseable content is reset, because refusing to load would make
     * one bad write permanent, and everything here is ciphertext the caller can re-obtain.
     *
     * Only a *deserialization* failure resets. Anything else, including a programming error surfacing as an
     * `IllegalArgumentException` from inside a serializer, propagates rather than costing the caller their data.
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
        } catch (e: SerializationException) {
            // SerializationException, not its IllegalArgumentException supertype. The supertype also catches a
            // genuine programming error raised from inside a serializer, and this handler does not rethrow, it
            // *writes*: it overwrites the store with an empty map, so a bug would become data loss reported as a
            // corrupt file. PayabliV2Decoding states the same rule for the same API, and the line below it is
            // where RedactedCause came from.
            //
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
            // isDirectory last: mkdirs returns false for a directory that already exists, and a sibling store
            // under the same missing parent can create it between this exists() and this mkdirs(). Without the
            // third clause the loser of that race reports StorageUnavailable with the directory sitting there.
            if (!parent.exists() && !parent.mkdirs() && !parent.isDirectory) {
                throw SecureStorageException.StorageUnavailable()
            }
            sweepOrphans()
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
     * therefore reclaimed on the next write **or on any [remove]**, rather than at the moment it is created.
     * [remove] sweeps even when the key is absent, since otherwise the one call that promises deletion would be
     * the one call that never looks.
     *
     * **The match is a shape, not a prefix**, because the lock only covers this store's own file. `fileName` is
     * a parameter, so a sibling store on `store.json2` is legitimate, and a prefix match would delete its temp
     * mid-write and fail its rename. It would also reach an unrelated host-app file in a directory the host app
     * owns too. Requiring the delimiter and an all-digit middle means only this store's own temp names match:
     * `store.json2...` fails the prefix, and `store.json.backup.7.tmp` fails the shape.
     *
     * Deleting a match is then safe, because the caller holds this file's lock, the store is single-process, and
     * the sweep runs before this write's own temp exists.
     *
     * **A sweep that cannot do its job fails.** Both of its silent outcomes, a directory that cannot be listed and
     * an orphan that cannot be deleted, used to leave the caller believing the entry was gone.
     */
    private fun sweepOrphans() {
        // A missing parent is nothing to sweep. A parent that exists but cannot be listed is a failure, and the
        // two are the same null from listFiles, which is why they are separated before it is called.
        val parent = file.parentFile ?: return
        if (!parent.isDirectory) return

        val prefix = tempPrefix()
        val orphans =
            parent.listFiles { candidate -> isOwnTemp(candidate.name, prefix) }
                ?: throw SecureStorageException.StorageUnavailable()

        orphans.forEach { orphan ->
            when {
                orphan.delete() -> logger.warn { "discarded an unfinished secure storage write" }
                // Reported, not swallowed: remove promises deletion, and returning normally with a decryptable
                // blob still on disk is the case this sweep exists to prevent.
                orphan.exists() -> throw SecureStorageException.StorageUnavailable()
                // Otherwise it vanished between the listing and the delete, which costs nothing.
            }
        }
    }

    /**
     * `pbl<identity>.`, a fixed length whatever the store is called.
     *
     * Two properties the previous `<fileName>.` form did not have. It is never shorter than the three characters
     * `File.createTempFile` demands, which a one-character file name violated with a raw `IllegalArgumentException`
     * escaping this class entirely. And because every prefix is the same length, no prefix can be a prefix of
     * another, so the `store` versus `store2` overlap the delimiter was patching cannot arise at all.
     */
    private fun tempPrefix(): String = TEMP_NAME_PREFIX + identity + TEMP_DELIMITER

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
        // Length first, because the prefix ends with the delimiter and the suffix begins with one, so they can
        // overlap: `<prefix>tmp` satisfies both of the checks below while being shorter than the two combined, and
        // the slice would then run backwards. That threw StringIndexOutOfBoundsException out of the sweep, past
        // write()'s IOException and SecurityException handlers, failing every write and every absent-key remove for
        // as long as such a sibling existed.
        if (name.length <= prefix.length + TEMP_SUFFIX.length) return false
        if (!name.startsWith(prefix) || !name.endsWith(TEMP_SUFFIX)) return false
        val middle = name.substring(prefix.length, name.length - TEMP_SUFFIX.length)
        return middle.all { it in '0'..'9' }
    }

    private companion object {
        private const val TEMP_SUFFIX = ".tmp"
        private const val TEMP_DELIMITER = "."
        private const val TEMP_NAME_PREFIX = "pbl"

        private val SERIALIZER = MapSerializer(String.serializer(), String.serializer())

        /** One lock per backing file, shared by every instance over it. */
        private val locks = HashMap<String, Mutex>()

        private fun lockFor(identity: String): Mutex =
            // Keyed by the identity the caller already resolved, so the alias, the lock and the temp prefix cannot
            // disagree about what one store is. A plain map under a monitor, not ConcurrentHashMap.computeIfAbsent,
            // which is API 24 against this module's floor of 23. Lint caught that; contention is one lookup per
            // instance.
            synchronized(locks) { locks.getOrPut(identity) { Mutex() } }
    }
}
