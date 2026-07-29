package com.payabli.sdk.core.network

import com.payabli.sdk.core.model.PayabliErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fixed contract of [AuthRecoveryPolicy]. `AuthenticatedTransportTest` covers the mechanism that runs it,
 * including that the mechanism consults this rather than re-deriving the status.
 */
class AuthRecoveryPolicyTest {
    private val policy = AuthRecoveryPolicy()

    private fun response(status: Int) = PayabliResponse(statusCode = status)

    @Test
    fun `only a 401 is a credential rejection`() {
        assertTrue(policy.isCredentialRejection(response(401)))
    }

    @Test
    fun `a success is not a credential rejection`() {
        assertFalse(policy.isCredentialRejection(response(200)))
        assertFalse(policy.isCredentialRejection(response(204)))
    }

    @Test
    fun `the credential-adjacent statuses are not rejections this policy recovers`() {
        // 402 is an authoritative decline about the payment, not about the caller: replaying it after a
        // refresh would present the same instrument to the same answer.
        assertFalse("402 decline", policy.isCredentialRejection(response(402)))
        // 403 is an authorization verdict on a valid credential. A fresh token carries the same grants.
        assertFalse("403 forbidden", policy.isCredentialRejection(response(403)))
        // 410 is a burned session. No refresh revives it; recovery is re-initialization.
        assertFalse("410 session burned", policy.isCredentialRejection(response(410)))
    }

    @Test
    fun `a transient failure is left to the retry policy`() {
        assertFalse(policy.isCredentialRejection(response(429)))
        assertFalse(policy.isCredentialRejection(response(500)))
        assertFalse(policy.isCredentialRejection(response(503)))
    }

    @Test
    fun `an exhausted recovery is token expired`() {
        assertEquals(PayabliErrorCode.TOKEN_EXPIRED, policy.exhausted().code)
    }

    @Test
    fun `an exhausted recovery says what happened in reason and keeps it out of message`() {
        val failure = policy.exhausted()

        assertTrue("reason should say what happened", failure.reason.contains("refresh", ignoreCase = true))
        // PayabliException is Exception(code.wireName), so reason never reaches `message`. That is the
        // family's redaction rule rather than this policy's doing: reason and detail are displayable and
        // never loggable, and `message` is what reaches logs and crash reports.
        assertEquals(PayabliErrorCode.TOKEN_EXPIRED.wireName, failure.message)
        assertFalse("no server text relayed", failure.reason.contains("401"))
    }

    @Test
    fun `token expired is excluded from the retryable set, so the two policies do not overlap`() {
        // The division of labour, asserted rather than only documented: if TOKEN_EXPIRED were retryable,
        // Retry would replay a terminal rejection that this policy already gave up on.
        assertFalse(PayabliErrorCode.TOKEN_EXPIRED in RetryPolicy.RETRYABLE_CODES)
    }
}
