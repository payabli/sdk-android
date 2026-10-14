package com.payabli.sdk.core.devicekey.platform

import com.payabli.sdk.core.devicekey.DeviceKey
import com.payabli.sdk.core.logging.LogCategory
import com.payabli.sdk.core.logging.LoggerRegistry
import com.payabli.sdk.core.logging.SdkLogger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Builds the shipping [DeviceKey]: an EC P-256 keypair in the Android Keystore, at one fixed alias.
 *
 * **It takes no `Context` and opens no file.** The alias is fixed in the SDK, so there is nothing to record
 * and nowhere to record it. What is left is the dispatch and the create-if-absent policy.
 *
 * **Safe to call from Main.** Every Keystore call is blocking, and generating a key in a secure element takes
 * tens of milliseconds, so the whole body is dispatched.
 *
 * **The dispatcher is required.** Only the layer the integrating app calls picks one; nothing internal
 * defaults, because a default is invisible at the call site and a composition that omitted it would run on the
 * real `Dispatchers.IO` while every layer above believed otherwise.
 */
internal object DeviceKeyFactory {
    /**
     * The key at the alias, generating one if there is none. Reuse falls out of the alias being fixed, so a
     * caller interrupted between generating a key and the service accepting it presents the same key again.
     * Whether the service accepted it is not answered here: nothing in the key store distinguishes the two.
     *
     * @param dispatcher where the key store calls run.
     * @param logger where the key's lifecycle is logged.
     * @return the key at the alias, created if there was none.
     */
    suspend fun deviceKey(
        dispatcher: CoroutineDispatcher,
        logger: SdkLogger = LoggerRegistry.of(LogCategory.CORE),
    ): DeviceKey =
        withContext(dispatcher) {
            KeystoreDeviceKey(logger).apply { ensureKey(mayCreate = true) }
        }
}
