package com.payabli.example.app.demo.qa

import java.math.BigDecimal
import kotlin.random.Random

/**
 * A different amount for every attempt, so two rows from two devices are told apart by the figure alone.
 *
 * Whole cents, between two and fifteen dollars: above the range where a paypoint's own minimum could refuse
 * it, and small enough that a run of them costs nothing.
 */
object QaAmount {
    private const val MIN_CENTS = 200
    private const val MAX_CENTS = 1499
    private const val CENTS_SCALE = 2

    /** Two decimal places exactly, built from the cents rather than rounded into them. */
    fun random(random: Random = Random.Default): BigDecimal =
        BigDecimal.valueOf(random.nextInt(MIN_CENTS, MAX_CENTS + 1).toLong(), CENTS_SCALE)
}
