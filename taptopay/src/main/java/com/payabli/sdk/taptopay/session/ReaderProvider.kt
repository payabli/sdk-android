package com.payabli.sdk.taptopay.session

import com.payabli.sdk.taptopay.attestation.device.ReaderCredentials

/**
 * The card reader, as a session sees it.
 *
 * No implementation in this module. The reader arrives with the charge work.
 */
internal interface ReaderProvider {
    /**
     * Hands the reader what it needs to talk to its own service.
     *
     * **Do not keep [credentials] beyond this call.** They hold live vendor secrets, they are not stored
     * anywhere by this SDK, and a session that needs them again fetches them again.
     */
    suspend fun configure(credentials: ReaderCredentials)

    /** Brings the reader up. Fails if [configure] was not called first, or if its credentials were refused. */
    suspend fun prepareReader()
}
