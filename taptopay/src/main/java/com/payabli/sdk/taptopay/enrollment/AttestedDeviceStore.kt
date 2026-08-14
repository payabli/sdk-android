package com.payabli.sdk.taptopay.enrollment

import com.payabli.sdk.core.logging.LogCategory
import com.payabli.sdk.core.logging.LogField
import com.payabli.sdk.core.logging.LoggerRegistry
import com.payabli.sdk.core.logging.SdkLogger
import com.payabli.sdk.core.logging.warn
import com.payabli.sdk.core.network.PayabliJson
import com.payabli.sdk.core.storage.PayabliSecureStorage
import com.payabli.sdk.core.storage.SecureStorageException
import kotlinx.serialization.SerializationException

/**
 * The one entry [AttestedDevice] lives in, and the rules for reading it back.
 *
 * One entry holds the whole record, so there is no ordering between its parts and no window where half of
 * it is present. No rollback is needed.
 *
 * The entry name is fixed. It cannot carry the paypoint, because the store offers no enumeration: a name
 * built from a value that changes leaves an entry that nothing can find and nothing can remove. The paypoint
 * lives inside the record, where a mismatch is checked instead of searched for.
 *
 * No dispatcher: [PayabliSecureStorage] suspends and already holds the one it was built with. A second hop
 * onto the same pool would be a hop for the look of the thing.
 */
internal class AttestedDeviceStore(
    private val storage: PayabliSecureStorage,
    private val logger: SdkLogger = LoggerRegistry.of(LogCategory.TAP_TO_PAY),
) {
    /**
     * The stored record, or null when there is nothing usable to read.
     *
     * **Null and a failure are different answers and must stay that way.** Null means the record is gone,
     * and the correct response is a cold start. A failure means the store could not be read *this time*, and
     * treating that as null would run the cold sequence against a device the service may still hold as
     * active, which retires it and costs the merchant a fresh activation code. The store's own contract
     * separates the two, and this is the caller that has to honour it:
     *
     * - the key was lost, or this entry alone could not be authenticated: the data is gone, so null;
     * - the record decoded to nothing recognisable: also gone, and the entry is dropped on the way out;
     * - the platform's cipher or the file was unavailable: **raised**, because the record may be perfectly
     *   fine and unreadable only for a moment.
     */
    suspend fun read(): AttestedDevice? {
        val bytes =
            try {
                storage.get(ENTRY)
            } catch (lost: SecureStorageException.KeyInvalidated) {
                reportLost(lost, "key_invalidated")
                return null
            } catch (unreadable: SecureStorageException.ValueUnreadable) {
                reportLost(unreadable, "value_unreadable")
                return null
            }
                ?: return null

        return try {
            PayabliJson.format.decodeFromString(AttestedDevice.serializer(), bytes.decodeToString())
        } catch (malformed: SerializationException) {
            // Narrowed to the serializer's own failure. A storage failure raised by the read above must
            // not be swallowed here on its way past.
            reportLost(malformed, "undecodable")
            storage.remove(ENTRY)
            null
        } finally {
            bytes.fill(0)
        }
    }

    /** Replaces the record. One call, so there is no half-written state to compensate for. */
    suspend fun write(record: AttestedDevice) {
        val bytes = PayabliJson.format.encodeToString(AttestedDevice.serializer(), record).encodeToByteArray()
        try {
            storage.set(ENTRY, bytes)
        } finally {
            // The store neither copies what it is given nor wipes it, so the caller owns both ends.
            bytes.fill(0)
        }
    }

    /** Forgets the device. Never touches the key, and never another consumer's entries. */
    suspend fun clear(): Unit = storage.remove(ENTRY)

    /** One record for every way the stored identity can turn out to be unreadable, naming which. */
    private fun reportLost(
        cause: Throwable,
        state: String,
    ) = logger.warn(cause, LogField.safe("event", EVENT_LOST), LogField.safe("state", state)) {
        "stored device identity is gone"
    }

    private companion object {
        /**
         * Versioned for the reason the key handle is: if the record's shape ever changes, the next version
         * takes a new name and removes this one explicitly, because this is the last code that knows it.
         */
        const val ENTRY = "com.payabli.sdk.taptopay.device.v1"
        const val EVENT_LOST = "device_identity_lost"
    }
}
