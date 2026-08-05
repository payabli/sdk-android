package com.payabli.sdk.core.devicekey.impl

import java.security.PrivateKey
import java.security.Signature

/**
 * `SHA256withECDSA` over a payload, producing the DER signature the verifier expects.
 *
 * Plain platform signing with no key store in it, so a private key from anywhere signs the same way and
 * this is provable without a device. What is device-bound is obtaining the key, not using it.
 *
 * Failures are left to propagate as `GeneralSecurityException`. Whatever holds the key knows whether an
 * `InvalidKeyException` means the key is gone or merely unsuitable, and that distinction cannot be made
 * here.
 */
internal object EcdsaSigner {
    const val ALGORITHM: String = "SHA256withECDSA"

    fun sign(
        key: PrivateKey,
        payload: ByteArray,
    ): ByteArray =
        Signature.getInstance(ALGORITHM).run {
            initSign(key)
            update(payload)
            sign()
        }
}
