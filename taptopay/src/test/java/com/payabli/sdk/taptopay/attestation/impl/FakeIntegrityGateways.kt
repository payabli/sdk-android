package com.payabli.sdk.taptopay.attestation.impl

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** What a fake gateway returns when nothing else is scripted. */
internal const val FAKE_TOKEN = "opaque.integrity.token"

/** A cloud project number for tests. Nothing reads it except the assertions that check it was passed. */
internal const val FAKE_CLOUD_PROJECT = 424242L

/**
 * A standard gateway whose two steps are scripted independently.
 *
 * [prepares] counts preparations, which is the number most of these tests are really about: preparing is
 * the expensive half, and almost every guarantee in [StandardAttestor] is a statement about how often it
 * happens. Each preparation gets an index, handed to both behaviours, so a script can say "the requester
 * from the first preparation is the one that goes stale".
 */
internal class FakeStandardGateway(
    private val onPrepare: suspend (attempt: Int) -> Unit = {},
    private val onRequest: suspend (preparation: Int, requestHash: String) -> String = { _, _ -> FAKE_TOKEN },
) : StandardIntegrityGateway {
    val prepares: AtomicInteger = AtomicInteger()
    val requestHashes: MutableList<String> = CopyOnWriteArrayList()
    val cloudProjectNumbers: MutableList<Long> = CopyOnWriteArrayList()

    override suspend fun prepareProvider(cloudProjectNumber: Long): StandardTokenRequester {
        val attempt = prepares.incrementAndGet()
        cloudProjectNumbers += cloudProjectNumber
        onPrepare(attempt)
        return StandardTokenRequester { requestHash ->
            requestHashes += requestHash
            onRequest(attempt, requestHash)
        }
    }
}

/** A classic gateway. One step, so one script. */
internal class FakeClassicGateway(
    private val onRequest: suspend (nonce: String, cloudProjectNumber: Long?) -> String = { _, _ -> FAKE_TOKEN },
) : ClassicIntegrityGateway {
    val nonces: MutableList<String> = CopyOnWriteArrayList()
    val cloudProjectNumbers: MutableList<Long?> = CopyOnWriteArrayList()

    override suspend fun requestToken(
        nonce: String,
        cloudProjectNumber: Long?,
    ): String {
        nonces += nonce
        cloudProjectNumbers += cloudProjectNumber
        return onRequest(nonce, cloudProjectNumber)
    }
}
