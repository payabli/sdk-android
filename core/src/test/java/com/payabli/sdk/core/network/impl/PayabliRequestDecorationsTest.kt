package com.payabli.sdk.core.network.impl

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The golden-order test. It exists so that inserting or reordering a decoration cannot happen without a
 * deliberate edit here, which is what keeps CONTRIBUTORS-before-BINDERS from being violated silently.
 *
 * When the first decoration lands, replace the expectation with the exact class sequence rather than
 * loosening the assertion.
 */
class PayabliRequestDecorationsTest {
    @Test
    fun `the declared chain is exactly what is expected`() {
        assertEquals(emptyList<Class<*>>(), PayabliRequestDecorations.chain.map { it.javaClass })
    }
}
