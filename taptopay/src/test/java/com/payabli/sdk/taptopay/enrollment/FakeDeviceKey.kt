package com.payabli.sdk.taptopay.enrollment

import com.payabli.sdk.core.devicekey.DeviceKey
import com.payabli.sdk.core.devicekey.DeviceKeyException
import com.payabli.sdk.core.devicekey.DevicePublicKey
import com.payabli.sdk.core.devicekey.DeviceSignature

/**
 * A [DeviceKey] that records what was asked of it, and refuses to be deleted.
 *
 * [delete] both counts and throws. The count lets a test assert zero however the call was made; the throw
 * makes a deletion loud where it happens. `AssertionError` because
 * this module narrows its catches to specific exception types precisely so an `Error` travels unimpeded — but
 * the counter is what the assertions read, because a future `runCatching` somewhere would swallow the throw
 * and the count would still be right.
 *
 * A key built `lost` has nothing at the alias: reads and signatures throw `KeyLost` until [provision] creates
 * a key with [PROVISIONED_IDENTITY]. `provisionFailure` makes [provision] throw instead, and
 * `provisionLeavesNoKey` makes it return with the alias still empty.
 */
internal class FakeDeviceKey(
    private var identity: String = KEY_IDENTITY,
    /** Raised by [publicKey] when set, for the paths that have to survive a key store that will not answer. */
    private val publicKeyFailure: Throwable? = null,
    /** Raised by [sign] when set. */
    private val signFailure: Throwable? = null,
    private var lost: Boolean = false,
    private val provisionFailure: Throwable? = null,
    private val provisionLeavesNoKey: Boolean = false,
) : DeviceKey {
    var deletions: Int = 0
        private set

    var publicKeyReads: Int = 0
        private set

    var provisions: Int = 0
        private set

    val signedPayloads: MutableList<ByteArray> = mutableListOf()

    override fun publicKey(): DevicePublicKey {
        publicKeyReads++
        if (lost) throw DeviceKeyException.KeyLost()
        publicKeyFailure?.let { throw it }
        return DevicePublicKey(point = POINT.copyOf(), identity = identity)
    }

    override fun sign(payload: ByteArray): DeviceSignature {
        signedPayloads += payload.copyOf()
        if (lost) throw DeviceKeyException.KeyLost()
        signFailure?.let { throw it }
        return DeviceSignature(signature = SIGNATURE.copyOf(), identity = identity)
    }

    override fun provision() {
        provisions++
        provisionFailure?.let { throw it }
        if (lost && !provisionLeavesNoKey) {
            identity = PROVISIONED_IDENTITY
            lost = false
        }
    }

    override fun delete() {
        deletions++
        throw AssertionError("the enrollment sequence must never delete the device key")
    }

    /** Stands in for the key store having replaced the key at the handle. */
    fun replaceKey(newIdentity: String) {
        identity = newIdentity
    }

    companion object {
        const val KEY_IDENTITY = "key-identity-value"

        /** The identity of the key [provision] creates in place of a lost one. */
        const val PROVISIONED_IDENTITY = "provisioned-key-identity-value"

        /** An X9.62 uncompressed point is 65 bytes and starts 0x04. Shaped right so encoding is exercised. */
        val POINT: ByteArray = ByteArray(65) { index -> if (index == 0) 0x04 else (index + 1).toByte() }
        val SIGNATURE: ByteArray = ByteArray(70) { index -> (index + 3).toByte() }
    }
}
