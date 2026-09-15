package com.payabli.sdk.taptopay.adapters.platform

import com.payabli.sdk.taptopay.adapters.ReaderFailureKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which vendor code means what, asserted by code rather than by kind.
 *
 * Everything else that touches a refusal builds a `CardReaderFailure` with the kind already chosen, so
 * the step that picks it was covered nowhere: emptying the code sets would have left the suite green
 * while every denial reported itself retryable, which is the defect the classification exists to prevent.
 */
class RefusalClassificationTest {
    @Test
    fun `a documented refusal is a denial`() {
        // Each of these has a stated meaning in the vendor's error table. 677 is the one this branch was
        // opened over: a device the vendor has suspended or deactivated.
        for (code in listOf("677", "018", "670", "202", "745")) {
            assertEquals(code, ReaderFailureKind.DEVICE_DENIED, refusalKind(code))
        }
    }

    @Test
    fun `705 is a denial the vendor has not explained`() {
        // Undocumented, and terminal on observation alone. It moves to the documented set when the vendor
        // answers, or out of both if it turns out transient.
        assertEquals(ReaderFailureKind.DEVICE_DENIED_UNCONFIRMED, refusalKind("705"))
    }

    @Test
    fun `a code nobody listed is not treated as either`() {
        // A terminal code filed as retryable costs a wasted retry; the reverse hides an outage. Neither
        // guess is made.
        assertEquals(ReaderFailureKind.UNCLASSIFIED, refusalKind("999"))
        assertEquals(ReaderFailureKind.UNCLASSIFIED, refusalKind(null))
        assertEquals(ReaderFailureKind.UNCLASSIFIED, refusalKind(""))
    }

    @Test
    fun `a code is matched exactly, not by its digits appearing somewhere`() {
        // The vendor sends a string, and a set membership is the whole check. This fails the moment that
        // becomes a prefix or a contains.
        assertEquals(ReaderFailureKind.UNCLASSIFIED, refusalKind("6770"))
        assertEquals(ReaderFailureKind.UNCLASSIFIED, refusalKind(" 677"))
    }
}
