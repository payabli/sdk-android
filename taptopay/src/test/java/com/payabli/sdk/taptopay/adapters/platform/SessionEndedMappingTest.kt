package com.payabli.sdk.taptopay.adapters.platform

import com.fiserv.commercehub.ttp.provider.exception.FSSDKInternalSDKException
import com.fiserv.commercehub.ttp.provider.exception.FSSDKInternalSDKUninitializedException
import com.payabli.sdk.taptopay.adapters.CardReaderFailure
import com.payabli.sdk.taptopay.adapters.ReaderFailureKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

private val TEST_TIMEOUT = 5.seconds

/**
 * The vendor's refusals for an ended session against the kind each lands on. Two differ only in text, copied
 * here from the pinned vendor version. Nothing reads the vendor's copy, so a version bump that rewords them
 * passes this test and reports an unknown outcome: a bump re-reads both texts.
 */
class SessionEndedMappingTest {
    @Test
    fun `a session the vendor reports expired or inactive is unusable`() =
        runTest(timeout = TEST_TIMEOUT) {
            val ended =
                listOf(
                    FSSDKInternalSDKException("Session expired. Please activate the session using initializeSession"),
                    FSSDKInternalSDKException(
                        "Session is currently inactive. Please activate the session using initializeSession",
                    ),
                    FSSDKInternalSDKUninitializedException(),
                )
            for (raised in ended) {
                assertEquals(raised.toString(), ReaderFailureKind.SESSION_UNUSABLE, kindOf(raised))
            }
        }

    @Test
    fun `another internal refusal stays unclassified`() =
        runTest(timeout = TEST_TIMEOUT) {
            for (raised in listOf(FSSDKInternalSDKException("Session expired"), FSSDKInternalSDKException(null))) {
                assertEquals(raised.toString(), ReaderFailureKind.UNCLASSIFIED, kindOf(raised))
            }
        }

    private suspend fun kindOf(raised: Exception): ReaderFailureKind {
        val failure = runCatching { mappingFailures<Unit> { throw raised } }.exceptionOrNull()
        assertTrue(raised.toString(), failure is CardReaderFailure)
        return (failure as CardReaderFailure).kind
    }
}
