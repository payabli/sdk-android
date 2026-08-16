package com.payabli.sdk.taptopay.provider

import com.payabli.sdk.taptopay.attestation.device.ReaderCredentials

/**
 * The card reader, as this SDK sees it.
 *
 * No implementation in this module. The reader arrives with the adapter work.
 *
 * **The four calls are four phases and they run in this order.** Eligibility asks whether this handset can
 * take a contactless payment at all, and runs before any credential exists. [configure] hands over the
 * vendor's credentials. [prepareReader] brings the reader up with them. [startReading] takes one payment.
 * A session runs the first three; a charge runs the fourth.
 *
 * **[startReading] both reads the card and charges it.** The vendor reader this SDK ships against talks to
 * its own processor directly, so no Payabli code sees the card and no Payabli key could decrypt it. What
 * comes back is the processor's own answer, which the transaction client forwards to Payabli unread.
 */
internal interface TapToPayProvider {
    /**
     * Whether this device can take contactless payments.
     *
     * Runs before anything else and before any credential is fetched, so an implementation may look only at
     * the platform, the hardware and the app's own entitlements. Throws [DeviceIneligibleException] when the
     * answer is no.
     */
    suspend fun checkEligibility()

    /**
     * Hands the reader what it needs to talk to its own service.
     *
     * **Do not keep [credentials] beyond this call.** They hold live vendor secrets, they are not stored
     * anywhere by this SDK, and a session that needs them again fetches them again.
     */
    suspend fun configure(credentials: ReaderCredentials)

    /** Brings the reader up. Fails if [configure] was not called first, or if its credentials were refused. */
    suspend fun prepareReader()

    /**
     * Runs one contactless payment: the tap, and the charge that follows it.
     *
     * The payment has already been opened at Payabli by the time this is called, and
     * [CardReadRequest.merchantTransactionId] is the identifier it was opened under. An implementation has to
     * give that identifier to its processor, because it is the only key the reconciliation reads the outcome
     * back by.
     */
    suspend fun startReading(request: CardReadRequest): CardReadResult
}
