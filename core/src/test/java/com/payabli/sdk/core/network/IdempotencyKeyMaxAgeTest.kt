package com.payabli.sdk.core.network

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration.Companion.minutes

/** The service's two minutes, plus a token fetch, the request, a refresh and the replay at thirty seconds each. */
class IdempotencyKeyMaxAgeTest {
    @Test
    fun `a held key is worth resending for four minutes`() {
        assertEquals(4.minutes, IDEMPOTENCY_KEY_MAX_AGE)
    }
}
