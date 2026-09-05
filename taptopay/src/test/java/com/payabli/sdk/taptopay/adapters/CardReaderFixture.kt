package com.payabli.sdk.taptopay.adapters

import com.payabli.sdk.taptopay.attestation.device.ReaderCredentials
import kotlinx.coroutines.awaitCancellation

/** A complete set, so a test that is about one field changes only that one. */
internal fun readerCredentials(
    platform: String = "android",
    secretKey: String = "a-secret-key",
    apiKey: String = "an-api-key",
    merchantId: String = "a-merchant",
    environment: String = "sandbox",
    currencyCode: String = "USD",
    merchantName: String = "A Shop",
    merchantCategoryCode: String = "5999",
    terminalId: String = "a-terminal",
    ppId: String = "a-profile",
    hostPort: String = "reader.example:4443",
): ReaderCredentials =
    ReaderCredentials(
        platform = platform,
        secretKey = secretKey,
        apiKey = apiKey,
        merchantId = merchantId,
        environment = environment,
        currencyCode = currencyCode,
        merchantName = merchantName,
        merchantCategoryCode = merchantCategoryCode,
        terminalId = terminalId,
        ppId = ppId,
        hostPort = hostPort,
    )

/** What the reader answers when a test does not care what it answers. */
internal fun chargeRecord(
    cardNetwork: String? = "VISA",
    transactionState: String? = "CAPTURED",
): ChargeRecord =
    ChargeRecord(
        gatewayResponse = GatewayResponseRecord(transactionState = transactionState),
        cardNetwork = cardNetwork,
    )

/** The one test double for [CardReaderGateway]. */
internal class FakeCardReaderGateway(
    private val prepareFailure: CardReaderFailure? = null,
    private val readFailure: CardReaderFailure? = null,
    private val record: ChargeRecord = chargeRecord(),
    /** Arming that never answers, which is what the reader did on a handset. */
    private val prepareNeverAnswers: Boolean = false,
) : CardReaderGateway {
    var prepareCount: Int = 0
        private set
    var lastArming: ReaderArming? = null
        private set
    var lastCharge: ReaderCharge? = null
        private set

    override suspend fun prepareReader(config: ReaderArming) {
        prepareCount++
        lastArming = config
        if (prepareNeverAnswers) awaitCancellation()
        prepareFailure?.let { throw it }
    }

    override suspend fun startReading(request: ReaderCharge): ChargeRecord {
        lastCharge = request
        readFailure?.let { throw it }
        return record
    }
}

/** Eligibility that answers yes, or raises whatever a test hands it. */
internal fun eligibility(failure: Throwable? = null): ReaderEligibility =
    ReaderEligibility { failure?.let { throw it } }
