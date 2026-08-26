package com.payabli.sdk.core.network.impl

import com.payabli.sdk.testutils.auth.testAuth
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The golden-order test. It exists so that inserting or reordering a decoration cannot happen without a
 * deliberate edit here, which is what keeps CONTRIBUTORS-before-BINDERS from being violated silently.
 *
 * The expectation is the exact class sequence, never a loosened assertion: a chain that is merely
 * non-empty would not catch a binder inserted ahead of a contributor.
 */
class RequestDecorationFactoryTest {
    @Test
    fun `the declared chain is exactly what is expected`() {
        assertEquals(
            listOf(BearerDecoration::class.java, JsonBodyDecoration::class.java),
            RequestDecorationFactory.chainFor(testAuth()).map { it.javaClass },
        )
    }
}
