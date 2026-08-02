package com.payabli.sdk.core.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The provably silent logger, which had no test because it has no caller in `:core`. It is
 * `@RestrictTo(LIBRARY_GROUP)` surface a capability artifact can reach, so its two guarantees are worth
 * pinning: nothing is loggable, and the message lambda is never invoked.
 *
 * The second is the one that matters. A no-op that still evaluated its lambda would compose the very
 * strings a caller chose this logger to avoid composing.
 */
class NoOpSdkLoggerTest {
    @Test
    fun `no level is ever loggable, including the most severe`() {
        LogLevel.entries.forEach { level ->
            assertFalse("level $level", NoOpSdkLogger.isLoggable(level))
        }
    }

    @Test
    fun `the message lambda is never invoked, at any level`() {
        var built = 0

        LogLevel.entries.forEach { level ->
            NoOpSdkLogger.log(level, listOf(LogField.safe("event", "probe")), RuntimeException("cause")) {
                built++
                "must never be composed"
            }
        }

        assertEquals(0, built)
    }

    @Test
    fun `it satisfies the logger contract, so it can stand in anywhere one is taken`() {
        val logger: SdkLogger = NoOpSdkLogger

        // Through the extension functions rather than log() directly, which is how call sites reach it.
        var built = 0
        val cause = RuntimeException("cause")

        logger.debug(cause) {
            built++
            "no"
        }
        logger.info(cause) {
            built++
            "no"
        }
        logger.fault(cause) {
            built++
            "no"
        }

        assertEquals(0, built)
    }
}
