package com.payabli.sdk.core.devicekey.platform

import android.content.Context
import com.payabli.sdk.core.devicekey.DeviceKey
import com.payabli.sdk.core.devicekey.impl.DeviceKeySlots
import com.payabli.sdk.core.logging.LogCategory
import com.payabli.sdk.core.logging.LoggerRegistry
import com.payabli.sdk.core.logging.SdkLogger
import com.payabli.sdk.core.storage.platform.SecureStorageFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Builds the shipping [DeviceKey]: an EC P-256 keypair in the Android Keystore, named by a slot store.
 *
 * Here rather than beside the slots because naming the Keystore is what makes a file unreachable from a unit
 * test, and this is the only other place in the package that does. Keeping it out leaves the encoding, the
 * signing and the slot bookkeeping testable on the JVM in full.
 *
 * **It takes a `Context` and derives the directory.** A [File] parameter would relocate the choice rather than
 * remove it, and the cost of getting it wrong is silent: two composition points picking different directories
 * get two slot stores, each with its own Keystore alias, each minting its own device key, and the second
 * strands the first. One answer per app, with no way to ask for another.
 *
 * **Both entry points are safe to call from Main.** Neither the storage layer's own dispatch nor a suspend
 * signature covers what happens here: resolving the store's canonical path, and every Keystore call, are
 * blocking, and they run between the suspending reads rather than inside them. Generating a key in a secure
 * element takes tens of milliseconds, so the whole body is dispatched.
 */
internal object DeviceKeyFactory {
    /**
     * Its own file, beside the token store rather than inside it.
     *
     * The token store is written on every refresh and is the likelier of the two to be found unparseable, and
     * a store that clears takes the names in it with it. Sharing one would let a token-store failure strand
     * the device key.
     */
    const val FILE_NAME: String = "payabli-devicekey.json"

    /**
     * The key awaiting attestation, minting one if there is none, together with the alias it is named by.
     *
     * The alias comes from the slots before any key exists, so a retry attests the key it already minted and
     * an interruption between the two leaves a name pointing at nothing, which reads as an absent key and is
     * recoverable. The reverse order would leave a key that nothing names.
     */
    suspend fun candidate(
        context: Context,
        logger: SdkLogger = LoggerRegistry.of(LogCategory.CORE),
    ): DeviceKey =
        withContext(Dispatchers.IO) {
            val alias = slots(context, logger).pendingOrNew()
            KeystoreDeviceKey(alias, logger).apply { ensureKey(mayCreate = true) }
        }

    /**
     * The attested key, or null when no key has been attested yet.
     *
     * `mayCreate` is false: the answer to an attested alias whose key is gone is re-attestation, not a fresh
     * key under the same name, which would sign with material the service has never seen.
     */
    suspend fun active(
        context: Context,
        logger: SdkLogger = LoggerRegistry.of(LogCategory.CORE),
    ): DeviceKey? =
        withContext(Dispatchers.IO) {
            val alias = slots(context, logger).active() ?: return@withContext null
            KeystoreDeviceKey(alias, logger).apply { ensureKey(mayCreate = false) }
        }

    /**
     * The slots over this app's device-key store.
     *
     * `noBackupFilesDir` for the reason the token store uses it: it keeps the file out of platform backup and
     * device transfer, where a restored name would point at a key that did not travel with it.
     *
     * The identity is taken from the store that was just opened rather than resolved again, so the Keystore
     * alias for the store, its file lock and the slot lock cannot disagree about which store they mean.
     *
     * Resolving the canonical path touches the filesystem, so callers dispatch this off Main.
     */
    internal fun slots(
        context: Context,
        logger: SdkLogger = LoggerRegistry.of(LogCategory.CORE),
    ): DeviceKeySlots {
        val opened =
            SecureStorageFactory.open(
                directory = context.applicationContext.noBackupFilesDir,
                fileName = FILE_NAME,
                logger = logger,
            )
        return DeviceKeySlots(opened.storage, opened.identity)
    }
}
