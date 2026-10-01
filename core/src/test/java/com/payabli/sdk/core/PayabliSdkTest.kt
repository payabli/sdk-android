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
    fun `the version is a release or a QA snapshot of one`() {
        assertTrue(
            "the SDK reports '${PayabliSdk.VERSION}'",
            PayabliSdk.VERSION.matches(PUBLISHED_VERSION),
        )
    }
}

/**
 * The two shapes this project publishes: `<major>.<minor>.<patch>`, which the release workflow requires of
 * `payabli.version`, and that followed by `-QA.<yyyymmddHHMMSS>`, which the snapshot workflow appends. No
 * leading zero in any numeric part, because a pre-release identifier of only digits compares numerically.
 */
private val PUBLISHED_VERSION =
    Regex("""(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(-QA\.\d{14})?""")
