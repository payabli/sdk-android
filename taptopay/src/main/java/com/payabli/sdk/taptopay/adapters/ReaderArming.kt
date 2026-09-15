package com.payabli.sdk.taptopay.adapters

import com.payabli.sdk.taptopay.attestation.device.ReaderCredentials

/** What the reader is brought up with. Two of these are live vendor secrets, so none of them is printed. */
internal class ReaderArming(
    val merchantId: String,
    val terminalId: String,
    val apiKey: String,
    val secretKey: String,
    val ppId: String,
    val hostPort: String,
    val environment: ReaderEnvironment,
    val currency: ReaderCurrency,
) {
    override fun toString(): String = "ReaderArming(environment=$environment)"
}

/** The environments the reader can be pointed at, by the vendor's own names. */
internal enum class ReaderEnvironment {
    DEV,
    QA,
    INT,
    CAT,
    CERT,
    PERF,
    PROD,
}

/** The currencies the reader can authorize in. */
internal enum class ReaderCurrency {
    USD,
    AUD,
}

/**
 * The credentials, read into something a reader can be built from.
 *
 * Nothing is defaulted. A blank field, an unknown environment and an unknown currency are all refused with
 * [CardReaderException.CredentialsUnusable], naming the field and never its value.
 */
internal fun ReaderCredentials.toArming(): ReaderArming =
    ReaderArming(
        merchantId = required("merchantId", merchantId),
        terminalId = required("terminalId", terminalId),
        apiKey = required("apiKey", apiKey),
        secretKey = required("secretKey", secretKey),
        ppId = required("ppId", ppId),
        hostPort = required("hostPort", hostPort),
        environment = readEnvironment(environment),
        currency = readCurrency(currencyCode),
    )

private fun required(
    field: String,
    value: String,
): String = value.trim().ifBlank { throw CardReaderException.CredentialsUnusable("$field is blank") }

/** Either vocabulary: one of the reader's own names, or the deployment tier the credentials name. */
private fun readEnvironment(value: String): ReaderEnvironment {
    val named = value.trim().uppercase()
    ReaderEnvironment.entries.firstOrNull { it.name == named }?.let { return it }
    return when (named) {
        "PRODUCTION" -> ReaderEnvironment.PROD
        "SANDBOX" -> ReaderEnvironment.CERT
        else -> throw CardReaderException.CredentialsUnusable("environment is not one this reader knows")
    }
}

private fun readCurrency(value: String): ReaderCurrency {
    val named = value.trim().uppercase()
    return ReaderCurrency.entries.firstOrNull { it.name == named }
        ?: throw CardReaderException.CredentialsUnusable("currencyCode is not one this reader can authorize in")
}
