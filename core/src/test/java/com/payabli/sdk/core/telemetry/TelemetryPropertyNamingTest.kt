package com.payabli.sdk.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every property key is snake_case, checked over the gate rather than over the declarations.
 *
 * A key reaches the wire only by being in [TelemetryCatalog]'s row for its event, so that is what is walked:
 * a key added straight into a row without a constant is caught here too. The declarations are walked as well,
 * so one added and not yet used cannot drift in the meantime.
 *
 * Both platforms bind to one catalog and the far side groups by key, so a `retryCount` beside a `retry_count`
 * is two columns for one thing and neither is complete.
 */
class TelemetryPropertyNamingTest {
    private val snakeCase = Regex("^[a-z][a-z0-9]*(_[a-z0-9]+)*$")

    @Test
    fun everyKeyAnEventMayCarryIsSnakeCase() {
        val offenders =
            TelemetryCatalog.events
                .flatMap { TelemetryCatalog.allowedKeys(it) }
                .distinct()
                .filterNot { snakeCase.matches(it) }

        assertEquals("not snake_case: $offenders", emptyList<String>(), offenders)
    }

    @Test
    fun everyDeclaredKeyIsSnakeCase() {
        val offenders = declaredKeys().filterNot { snakeCase.matches(it) }

        assertEquals("not snake_case: $offenders", emptyList<String>(), offenders)
    }

    /** The gate cannot pass a key nothing declares, so the two lists have to be the same one. */
    @Test
    fun everyKeyAnEventMayCarryIsDeclared() {
        val declared = declaredKeys().toSet()
        val used = TelemetryCatalog.events.flatMap { TelemetryCatalog.allowedKeys(it) }.toSet()

        assertTrue("in a catalog row and declared nowhere: ${used - declared}", declared.containsAll(used))
    }

    /** That the walk finds anything at all, so a naming check over an empty list cannot pass by default. */
    @Test
    fun theKeysAreFound() {
        assertTrue(declaredKeys().toString(), declaredKeys().size >= 10)
        assertTrue(TelemetryCatalog.events.isNotEmpty())
    }

    /**
     * The key the enum derives is the key the far side will take.
     *
     * Its rule is the union of both conventions, `^[a-z][A-Za-z0-9_]{0,31}$`, so a camelCase key would be
     * accepted too. The snake_case rule above is a choice; this one is not, because a key outside the union
     * is refused and the event dropped in silence.
     */
    @Test
    fun everyKeyIsOneTheFarSideAccepts() {
        val accepted = Regex("^[a-z][A-Za-z0-9_]{0,31}\$")
        val offenders = declaredKeys().filterNot { accepted.matches(it) }

        assertEquals("the far side would refuse: $offenders", emptyList<String>(), offenders)
    }

    /**
     * The exact key every event is grouped by at the far side.
     *
     * ktlint keeps an entry from being written in lower camel case, and permits upper camel case, which
     * lowercases to a different key: `DurationMs` would send `durationms` and still look snake_case. This is
     * what a re-spelling has to get past, and the message names the whole set.
     */
    @Test
    fun everyKeyIsTheOneTheFarSideAlreadyCountsBy() {
        assertEquals(
            listOf(
                "outcome",
                "code",
                "reason",
                "duration_ms",
                "attempt",
                "state",
                "from",
                "to",
                "step",
                "field",
            ),
            declaredKeys(),
        )
    }

    private fun declaredKeys(): List<String> = TelemetryProperty.entries.map { it.key }
}
