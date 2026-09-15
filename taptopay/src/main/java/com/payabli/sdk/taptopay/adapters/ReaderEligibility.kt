package com.payabli.sdk.taptopay.adapters

/**
 * Whether this handset can ever take a contactless payment.
 *
 * Permanent facts only. Anything a merchant can put right, a radio switched off among them, surfaces when
 * the reader is brought up.
 */
internal fun interface ReaderEligibility {
    /**
     * Throws [com.payabli.sdk.taptopay.provider.DeviceIneligibleException] when the answer is no.
     *
     * Runs before any credential is fetched, so it may look only at the platform and the hardware.
     */
    fun check()
}
