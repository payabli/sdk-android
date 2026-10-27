package com.payabli.sdk.taptopay.adapters.platform

import com.fiserv.commercehub.ttp.provider.exception.FSSDKNFCDisabledInternalException
import com.fiserv.commercehub.ttp.provider.exception.FSSDKNFCDisabledSDKException
import com.fiserv.commercehub.ttp.provider.exception.FSSDKNFCNotSupportSDKException
import com.fiserv.commercehub.ttp.provider.exception.FSSDKNFCNotSupportedInternalException
import com.payabli.sdk.taptopay.adapters.CardReaderFailure
import com.payabli.sdk.taptopay.adapters.ReaderFailureKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

private val TEST_TIMEOUT = 5.seconds

/**
 * The vendor's contactless exceptions against the kind each lands on. Everything else builds a
 * `CardReaderFailure` with the kind already chosen, so the catch that picks it is covered nowhere but
 * here.
 */
class ContactlessRadioMappingTest {
    @Test
    fun `a switched-off radio is its own kind`() =
        runTest(timeout = TEST_TIMEOUT) {
            for (raised in disabledRadios()) {
                assertEquals(raised.toString(), ReaderFailureKind.CONTACTLESS_DISABLED, kindOf(raised))
            }
        }

    @Test
    fun `a handset that cannot run a radio stays unavailable`() =
        runTest(timeout = TEST_TIMEOUT) {
            for (raised in unsupportedHandsets()) {
                assertEquals(raised.toString(), ReaderFailureKind.CONTACTLESS_UNAVAILABLE, kindOf(raised))
            }
        }

    private fun disabledRadios() =
        listOf(
            FSSDKNFCDisabledSDKException("the radio is switched off"),
            FSSDKNFCDisabledInternalException("nfc disabled", "the radio is switched off"),
        )

    private fun unsupportedHandsets() =
        listOf(
            FSSDKNFCNotSupportSDKException("no contactless radio"),
            FSSDKNFCNotSupportedInternalException("nfc not supported", "no contactless radio"),
        )

    private suspend fun kindOf(raised: Exception): ReaderFailureKind {
        val failure = runCatching { mappingFailures<Unit> { throw raised } }.exceptionOrNull()
        assertTrue(raised.toString(), failure is CardReaderFailure)
        return (failure as CardReaderFailure).kind
    }
}
