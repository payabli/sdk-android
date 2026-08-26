package com.payabli.sdk.testutils.logging

import com.payabli.sdk.core.logging.LogField
import com.payabli.sdk.core.logging.LogLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/** Real threads and a real clock, so the bound is wall time, as the storage fixture's own suite sets it. */
private val CONCURRENCY_TIMEOUT = 30.seconds

/**
 * Stands in for whatever a caller must not log. A card number would do the same job and read as one to a
 * secret scanner, and these tests are about where the flattening looks rather than about cards.
 */
private const val SENSITIVE = "SENTINEL-MUST-NOT-APPEAR"

private const val WRITERS = 8
private const val LINES_EACH = 2_000

/** Deep enough that a walk bounded by a plausible constant would stop before the value. */
private const val DEEP_CHAIN = 50

/**
 * The fixture's own tests, because every caller of [RecordingSdkLogger.everythingWritten] asserts that a
 * value is **absent** from it. A flattening that misses somewhere a value can hide reports absence for
 * something present, and no caller's assertion can tell the difference.
 */
class RecordingSdkLoggerTest {
    @Test
    fun `a value in an attached throwable is written`() {
        val logger = RecordingSdkLogger()

        logger.log(LogLevel.WARN, emptyList(), IllegalStateException(SENSITIVE)) { "failed" }

        assertTrue(logger.everythingWritten().contains(SENSITIVE))
    }

    @Test
    fun `a value in a wrapped cause is written`() {
        val logger = RecordingSdkLogger()
        val wrapped = IllegalStateException("outer", IllegalArgumentException(SENSITIVE))

        logger.log(LogLevel.WARN, emptyList(), wrapped) { "failed" }

        assertTrue(logger.everythingWritten().contains(SENSITIVE))
    }

    @Test
    fun `a value deep in the cause chain is written`() {
        val logger = RecordingSdkLogger()
        var chain: Throwable = IllegalArgumentException(SENSITIVE)
        repeat(DEEP_CHAIN) { link -> chain = IllegalStateException("link $link", chain) }

        logger.log(LogLevel.WARN, emptyList(), chain) { "failed" }

        assertTrue(logger.everythingWritten().contains(SENSITIVE))
    }

    /** What a `use` block produces: the body's failure, carrying the one from `close` as suppressed. */
    @Test
    fun `a value in a suppressed exception is written`() {
        val logger = RecordingSdkLogger()
        val thrown = IllegalStateException("body failed")
        thrown.addSuppressed(IllegalStateException("close failed", IllegalArgumentException(SENSITIVE)))

        logger.log(LogLevel.WARN, emptyList(), thrown) { "failed" }

        assertTrue(logger.everythingWritten().contains(SENSITIVE))
    }

    @Test
    fun `a cycle through suppressed terminates`() {
        val logger = RecordingSdkLogger()
        val outer = IllegalStateException("outer")
        val inner = IllegalStateException(SENSITIVE)
        outer.addSuppressed(inner)
        inner.addSuppressed(outer)

        logger.log(LogLevel.WARN, emptyList(), outer) { "failed" }

        assertTrue(logger.everythingWritten().contains(SENSITIVE))
    }

    @Test
    fun `a field list the caller keeps cannot change a record`() {
        val logger = RecordingSdkLogger()
        val fields = mutableListOf(LogField.safe("route", "/pay"))

        logger.log(LogLevel.INFO, fields, null) { "called" }
        fields.add(LogField.safe("statusCode", 500))

        assertEquals(listOf("route"), logger.records.single().fieldNames)
    }

    /**
     * The exposed list is a read-only view of the backing store, not a copy of it, so a caller that read
     * `records` before a write still sees the write.
     */
    @Test
    fun `records is a live read-only view`() {
        val logger = RecordingSdkLogger()
        val view = logger.records

        logger.log(LogLevel.INFO, emptyList(), null) { "called" }

        assertEquals(1, view.size)
    }

    /** `initCause` refuses a throwable as its own cause, so a cycle takes two of them. */
    @Test
    fun `a cyclic cause chain terminates`() {
        val logger = RecordingSdkLogger()
        val outer = IllegalStateException("outer")
        val inner = IllegalArgumentException("inner")
        outer.initCause(inner)
        inner.initCause(outer)

        logger.log(LogLevel.WARN, emptyList(), outer) { "failed" }

        assertTrue(logger.everythingWritten().contains("inner"))
    }

    @Test
    fun `a field name is written but its value is not`() {
        val logger = RecordingSdkLogger()

        logger.log(LogLevel.INFO, listOf(LogField.safe("route", "/pay")), null) { "called" }

        val written = logger.everythingWritten()
        assertTrue(written.contains("route"))
        // Pins the boundary rather than a defect: the value sits on a subtype internal to the module
        // that declares it, so a test asserting a value never reaches a field belongs in that module.
        assertFalse(written.contains("/pay"))
    }

    /**
     * Concurrent writers, because the loggers this module hands out are held by a transport that has every
     * request in flight at once. An unsynchronised list drops records here rather than reporting anything,
     * which would silently weaken every absence assertion built on it.
     */
    @Test
    fun `records survive concurrent writers`() =
        runTest(timeout = CONCURRENCY_TIMEOUT) {
            val logger = RecordingSdkLogger()

            (1..WRITERS)
                .map { writer ->
                    async(Dispatchers.IO) {
                        repeat(LINES_EACH) { line ->
                            logger.log(LogLevel.DEBUG, emptyList(), null) { "writer $writer line $line" }
                        }
                    }
                }.awaitAll()

            assertEquals(WRITERS * LINES_EACH, logger.records.size)
        }
}
