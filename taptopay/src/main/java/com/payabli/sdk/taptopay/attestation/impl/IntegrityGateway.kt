package com.payabli.sdk.taptopay.attestation.impl

/**
 * A platform error, carried across the seam as a plain integer.
 *
 * The integer is the whole point. Play Integrity's error constants live on two Android types, and its
 * exceptions are Play services types; anything above this line that touched either would be a file no
 * unit test could reach. So the gateway reduces a platform failure to its code here, and every decision
 * about what that code *means* is taken on this side, on the JVM, under test.
 *
 * The [cause] is kept for a stack trace and is never inspected.
 *
 * [errorCode] is null where the platform failed without one: a gateway converts **every** throwable it
 * sees, not only the two integrity exception types, because a caller must never receive a raw platform
 * exception from a Play services internal it cannot classify. A coded failure and an uncoded one are both
 * failures; only the coded one can be classified precisely.
 */
internal class IntegrityFailure(
    val errorCode: Int?,
    cause: Throwable? = null,
) : Exception("integrity error ${errorCode ?: "unreported"}", cause)

/**
 * The platform half of a standard request, which is two steps rather than one.
 *
 * Preparing a provider is a network round trip and its result is reusable; requesting a token against a
 * prepared provider is the cheap part. Splitting them is not an optimisation, it is the shape the platform
 * has: a provider expires independently of any request made through it.
 */
internal fun interface StandardIntegrityGateway {
    /** Prepares a provider for [cloudProjectNumber]. Throws [IntegrityFailure] for a platform error. */
    suspend fun prepareProvider(cloudProjectNumber: Long): StandardTokenRequester
}

/** A prepared provider. Reusable until the platform decides it is not. */
internal fun interface StandardTokenRequester {
    /** Requests a token bound to [requestHash]. Throws [IntegrityFailure] for a platform error. */
    suspend fun request(requestHash: String): String
}

/**
 * The platform half of a classic request, which is one step and keeps nothing.
 *
 * [cloudProjectNumber] is nullable because the platform makes it optional here: an app distributed through
 * Play has one already, by way of its Play Console linkage, and supplying it is only required where that
 * linkage does not exist. A standard request has no such fallback, which is why its gateway demands one.
 */
internal fun interface ClassicIntegrityGateway {
    /** Requests a token bound to [nonce]. Throws [IntegrityFailure] for a platform error. */
    suspend fun requestToken(
        nonce: String,
        cloudProjectNumber: Long?,
    ): String
}
