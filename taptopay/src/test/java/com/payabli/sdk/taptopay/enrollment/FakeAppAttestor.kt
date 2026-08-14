package com.payabli.sdk.taptopay.enrollment

import com.payabli.sdk.taptopay.attestation.AppAttestor
import com.payabli.sdk.taptopay.attestation.AttestationChallenge
import com.payabli.sdk.taptopay.attestation.AttestationToken

/** An [AppAttestor] that keeps the challenge it was handed and answers with whatever it was told to. */
internal class FakeAppAttestor(
    private val token: AttestationToken = AttestationToken(TOKEN),
    private val failure: Throwable? = null,
) : AppAttestor {
    val challenges: MutableList<AttestationChallenge> = mutableListOf()
    var warmUps: Int = 0
        private set

    override suspend fun attest(challenge: AttestationChallenge): AttestationToken {
        challenges += challenge
        failure?.let { throw it }
        return token
    }

    override suspend fun warmUp() {
        warmUps++
    }

    companion object {
        const val TOKEN = "attestation-token-value"
    }
}
