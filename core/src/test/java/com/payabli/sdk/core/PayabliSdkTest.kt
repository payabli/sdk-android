package com.payabli.sdk.core

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a host reads when it reports which SDK it is running.
 *
 * The same value goes to support and into the telemetry payload, so it is held to a shape both can read.
 */
class PayabliSdkTest {
    @Test
    fun `the version is a release version`() {
        assertTrue(
            "the SDK reports '${PayabliSdk.VERSION}'",
            PayabliSdk.VERSION.matches(Regex("""\d+\.\d+\.\d+(-[0-9A-Za-z.]+)?""")),
        )
    }
}
