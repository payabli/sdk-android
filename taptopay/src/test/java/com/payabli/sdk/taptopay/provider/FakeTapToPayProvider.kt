package com.payabli.sdk.taptopay.provider

import com.payabli.sdk.taptopay.attestation.device.ReaderCredentials

/** What the reader answers when a test does not care what it answers. */
internal fun cardRead(
    cardNetwork: String? = "Visa",
    providerResponse: String = """{"gatewayResponse":{"transactionState":"CAPTURED"}}""",
): CardReadResult = CardReadResult(cardNetwork = cardNetwork, providerResponse = providerResponse)

/**
 * The one test double for [TapToPayProvider].
 *
 * One rather than several: a second implementation of a four-method interface is a second set of answers to
 * the same questions, and the two drift on which of them throws.
 *
 * [lastReadRequest] is what the charge asked the reader for, which is the only way to assert that the
 * identifier Payabli minted is the one the reader was given. The shipping sibling's double records nothing
 * here, so that property is unassertable there.
 *
 * [trace] and [gate] belong to the serialization tests. The gate holds a run inside the reader long enough
 * for a second to try to enter it, and [sawOverlap] is raised from in there — which is the assertion worth
 * making, because checking the state afterwards can be satisfied by luck and a flag raised from inside the
 * shared resource cannot.
 */
internal class FakeTapToPayProvider(
    private val trace: MutableList<String> = mutableListOf(),
    private val gate: (suspend () -> Unit)? = null,
    private val eligibilityFailure: Throwable? = null,
    private val readResult: CardReadResult = cardRead(),
) : TapToPayProvider {
    var eligibilityCount: Int = 0
        private set
    var configureCount: Int = 0
        private set
    var prepareCount: Int = 0
        private set
    var lastCredentials: ReaderCredentials? = null
        private set
    var lastReadRequest: CardReadRequest? = null
        private set
    var sawOverlap: Boolean = false
        private set

    private var inside = false

    override suspend fun checkEligibility() {
        trace += "reader:eligibility"
        eligibilityCount++
        eligibilityFailure?.let { throw it }
    }

    override suspend fun configure(credentials: ReaderCredentials) {
        trace += "reader:configure"
        configureCount++
        lastCredentials = credentials
    }

    override suspend fun prepareReader() {
        trace += "reader:prepare"
        if (inside) sawOverlap = true
        inside = true
        try {
            prepareCount++
            gate?.invoke()
        } finally {
            inside = false
        }
    }

    override suspend fun startReading(request: CardReadRequest): CardReadResult {
        trace += "reader:read"
        lastReadRequest = request
        return readResult
    }
}
