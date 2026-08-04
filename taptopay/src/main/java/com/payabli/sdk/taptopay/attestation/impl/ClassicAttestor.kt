package com.payabli.sdk.taptopay.attestation.impl

import com.payabli.sdk.core.logging.LogCategory
import com.payabli.sdk.core.logging.LogField
import com.payabli.sdk.core.logging.LoggerRegistry
import com.payabli.sdk.core.logging.SdkLogger
import com.payabli.sdk.core.logging.error
import com.payabli.sdk.taptopay.attestation.AppAttestor
import com.payabli.sdk.taptopay.attestation.AttestationChallenge
import com.payabli.sdk.taptopay.attestation.AttestationException
import com.payabli.sdk.taptopay.attestation.AttestationToken
import com.payabli.sdk.taptopay.attestation.VerdictClass

/**
 * Attests through a classic request: one call, nothing kept between them.
 *
 * There is no provider to prepare and nothing to warm up, so [warmUp] does nothing and says so. That is
 * not an omission: a classic request reaches Google's servers every time by design, which is also why the
 * platform rates it for infrequent use and advises against caching what it returns.
 *
 * [cloudProjectNumber] is nullable here and required for a standard request, because the platform makes it
 * optional for this one: an app whose Play Console listing carries the linkage needs no explicit number.
 */
internal class ClassicAttestor(
    private val gateway: ClassicIntegrityGateway,
    private val cloudProjectNumber: Long? = null,
    private val ledger: ChallengeLedger = ChallengeLedger(),
    private val throttleGate: ThrottleGate = ThrottleGate(),
    private val logger: SdkLogger = LoggerRegistry.of(LogCategory.TAP_TO_PAY),
) : AppAttestor {
    /** Nothing to prepare. */
    override suspend fun warmUp(): Unit = Unit

    override suspend fun attest(challenge: AttestationChallenge): AttestationToken {
        require(challenge.verdictClass == VerdictClass.CLASSIC) {
            "this attestor makes classic requests; the challenge was built for ${challenge.verdictClass}"
        }
        // Before the challenge is spent, so a refused attempt does not burn a value the caller must replace.
        throttleGate.check()
        // Before the request, not after it. A challenge is spent by being offered.
        ledger.spend(challenge.value)

        val token =
            try {
                gateway.requestToken(challenge.value, cloudProjectNumber)
            } catch (failure: IntegrityFailure) {
                val mapped = PlayIntegrityErrorMapping.failureFor(failure.errorCode, VerdictClass.CLASSIC, failure)
                logger.error(
                    LogField.safe("event", "attestation_failed"),
                    LogField.safe("verdictClass", VerdictClass.CLASSIC.name),
                    // As a string; see the same field in StandardAttestor.
                    LogField.safe("errorCode", failure.errorCode?.toString()),
                ) { "classic integrity request failed" }
                if (mapped is AttestationException.Throttled) throttleGate.record()
                throw mapped
            }
        return AttestationToken(token)
    }
}
