package com.payabli.sdk.taptopay.session

import com.payabli.sdk.taptopay.attestation.device.ReaderCredentials

/**
 * The card reader, as a session sees it.
 *
 * Two calls and no implementation in this module yet. It exists now because without it the states between
 * fetching the credentials and being ready cannot be entered, and a state nothing can reach is a branch a
 * host writes and never runs.
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
