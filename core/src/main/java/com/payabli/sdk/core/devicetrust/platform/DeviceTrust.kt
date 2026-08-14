package com.payabli.sdk.core.devicetrust.platform

import android.content.Context
import androidx.annotation.RestrictTo
import androidx.annotation.VisibleForTesting
import com.payabli.sdk.core.PayabliSession
import com.payabli.sdk.core.devicekey.DeviceKey
import com.payabli.sdk.core.devicekey.platform.DeviceKeyFactory
import com.payabli.sdk.core.logging.LogCategory
import com.payabli.sdk.core.logging.LoggerRegistry
import com.payabli.sdk.core.logging.SdkLogger
import com.payabli.sdk.core.storage.PayabliSecureStorage
import com.payabli.sdk.core.storage.platform.SecureStorageFactory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * What this device has: its key, and somewhere to keep what the service said about it.
 *
 * Handed out together because a capability needs both or neither. Proving possession of the key is only
 * useful to something that remembers which identity the key was bound to, and remembering an identity is
 * only useful to something that can still sign for it.
 *
 * `@RestrictTo(LIBRARY_GROUP)`: reachable from the SDK's own artifacts, including a card-present capability
 * shipped as its own repository, and a Lint error in a host app's build. A host app has no business
 * provisioning device identity. It is the same register [PayabliSession.transport] is in and for the same
 * reason: a detached capability cannot build this itself, and nobody outside should.
 *
 * **Takes a `Context` and keeps none.** The reference is normalised to the application context, read once
 * for its no-backup directory, and dropped. The session does not retain it: [PayabliSession.initialize] is
 * idempotent by configuration value and excludes host bindings, so a retained context would be whichever
 * call arrived first. Passing the wrong one is not a leak, it is a different store — the alias follows the
 * file's canonical path, so the old blobs read as invalidated and the device re-registers silently.
 *
 * **The directory is not a parameter, and neither is the file name.** Both form the store's permanent
 * identity. This is the Android counterpart of a single keychain service: a second consumer adds entries
 * under its own reverse-DNS key names. A file that moves is a new file, with a new key and no way back.
 *
 * **Safe to call more than once.** No singleton or cache is needed: the storage layer takes its lock from
 * process-wide state keyed on the store's identity, so two instances over one path share one lock, and the
 * second call finds the key already at the fixed alias.
 *
 * **Two unrelated ways to fail.** A directory whose path cannot be resolved raises
 * `SecureStorageException.StorageUnavailable`; an unreachable key store raises `DeviceKeyException`.
 *
 * Not handed out, at any visibility: the cipher, either Keystore alias, the store's identity, the file, the
 * directory, and any way to clear the store wholesale. A capability gets encryption at rest, never the path
 * it rests at, and never a call that would take another consumer's entries with it. Removal is per entry, by
 * the consumer that wrote it.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public class DeviceTrust private constructor(
    /** This device's signing key, already provisioned. */
    @get:RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public val key: DeviceKey,
    /** The shared encrypted store. Prefix entry names reverse-DNS; the file is not yours alone. */
    @get:RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public val store: PayabliSecureStorage,
) {
    /** Never a value: one half is key material and the other is where identity is kept. */
    override fun toString(): String = "DeviceTrust()"

    public companion object {
        /**
         * Opens the store and provisions the key, on the dispatcher the session picked.
         *
         * No dispatcher parameter, because that would move the choice into the calling capability and there
         * would then be two places in the SDK that name one.
         */
        @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
        public suspend fun open(context: Context): DeviceTrust = openWith(context, PayabliSession.IO_DISPATCHER)

        /**
         * [open] with the dispatcher stated, so a test can hand in one it can recognise.
         *
         * Required and undefaulted for the reason every layer below the session is: a default here would be
         * invisible at the call site, and a test that substituted a dispatcher would be measuring a thread
         * the code never used.
         */
        @VisibleForTesting
        internal suspend fun openWith(
            context: Context,
            dispatcher: CoroutineDispatcher,
            logger: SdkLogger = LoggerRegistry.of(LogCategory.CORE),
        ): DeviceTrust =
            withContext(dispatcher) {
                // The store first. Opening it provisions no key material — the cipher's key is created on the
                // first write — so a directory that cannot be resolved leaves nothing behind. The other order
                // would create a Keystore key and then throw, orphaning it under an alias nothing names.
                val store =
                    SecureStorageFactory.create(
                        directory = context.applicationContext.noBackupFilesDir,
                        dispatcher = dispatcher,
                        logger = logger,
                    )
                DeviceTrust(key = DeviceKeyFactory.deviceKey(dispatcher, logger), store = store)
            }
    }
}
