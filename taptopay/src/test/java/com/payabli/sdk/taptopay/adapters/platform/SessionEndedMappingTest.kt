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
 * The vendor's refusals for a session that has ended against the kind each lands on. Two of them carry the
 * vendor's generic internal code and differ only in their text, so the texts are written out here: a
 * reworded one fails this test rather than reporting an ended session as an unknown outcome.
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
