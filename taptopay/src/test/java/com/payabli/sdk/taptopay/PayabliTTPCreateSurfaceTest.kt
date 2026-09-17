package com.payabli.sdk.taptopay

import android.content.Context
import com.payabli.sdk.core.PayabliSession
import com.payabli.sdk.taptopay.attestation.AttestationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the construction surface the ticket must not move: [PayabliTTP.create] takes a session and a
 * context only.
 */
class PayabliTTPCreateSurfaceTest {
    @Test
    fun `create takes session and context only`() {
        // Suspend methods carry a Continuation as the last JVM parameter; the host-facing arity is the
        // rest. The method lives on the companion, not on the class.
        val create =
            Class
                .forName("com.payabli.sdk.taptopay.PayabliTTP\$Companion")
                .methods
                .single {
                    it.name == "create" &&
                        it.parameterTypes.getOrNull(0) == PayabliSession::class.java
                }

        assertEquals(3, create.parameterCount)
        assertEquals(PayabliSession::class.java, create.parameterTypes[0])
        assertEquals(Context::class.java, create.parameterTypes[1])
        assertTrue(create.parameterTypes[2].name.contains("Continuation"))
    }
}

class MisconfiguredMessageTest {
    @Test
    fun `Misconfigured keeps its default message when none is supplied`() {
        val failure = AttestationException.Misconfigured(errorCode = -2)

        assertEquals("the integrity request was configured wrongly by this SDK", failure.message)
        assertEquals(-2, failure.errorCode)
    }

    @Test
    fun `Misconfigured accepts a ruled message without changing the default`() {
        val ruled =
            AttestationException.Misconfigured(
                errorCode = null,
                message = "this Payabli environment has no attestation project configured",
            )
        val mapped = AttestationException.Misconfigured(errorCode = -2)

        assertEquals(null, ruled.errorCode)
        assertEquals(
            "this Payabli environment has no attestation project configured",
            ruled.message,
        )
        assertEquals("the integrity request was configured wrongly by this SDK", mapped.message)
    }
}
