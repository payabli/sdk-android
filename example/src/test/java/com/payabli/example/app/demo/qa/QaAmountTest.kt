package com.payabli.example.app.demo.qa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import kotlin.random.Random

class QaAmountTest {
    @Test
    fun `every amount is inside the range and is whole cents`() {
        // Swept, because a bound off by a cent shows up in one draw out of thirteen hundred.
        val random = Random(seed = 1)

        repeat(10_000) {
            val amount = QaAmount.random(random)

            assertTrue("$amount is below two dollars", amount >= BigDecimal("2.00"))
            assertTrue("$amount is fifteen dollars or more", amount < BigDecimal("15.00"))
            assertEquals("$amount is not whole cents", 2, amount.scale())
        }
    }

    @Test
    fun `the draw varies rather than repeating one figure`() {
        // What is asserted is variety across a run, not that consecutive draws differ: the draw has no memory,
        // so two attempts can repeat and the customer and the order identifier are what tell rows apart.
        val random = Random(seed = 2)
        val drawn = List(20) { QaAmount.random(random) }

        assertTrue("twenty draws produced $drawn", drawn.distinct().size > 1)
    }
}
