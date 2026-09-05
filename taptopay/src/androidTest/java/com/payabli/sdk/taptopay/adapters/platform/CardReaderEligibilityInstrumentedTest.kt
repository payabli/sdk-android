package com.payabli.sdk.taptopay.adapters.platform

import android.content.pm.PackageManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.payabli.sdk.taptopay.provider.DeviceIneligibleException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The eligibility gate against this handset.
 *
 * Needs no credentials and no reader, so it runs wherever the module installs. What it asserts is the gate
 * agreeing with the device it is running on, in both directions.
 */
@RunWith(AndroidJUnit4::class)
class CardReaderEligibilityInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun theGateAgreesWithWhatThisHandsetIs() {
        val qualifies =
            Build.VERSION.SDK_INT >= CARD_PRESENT_MIN_API &&
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC)

        val refusal = runCatching { CardReaderEligibility(context).check() }.exceptionOrNull()

        assertEquals(
            "API ${Build.VERSION.SDK_INT}, refusal was $refusal",
            qualifies,
            refusal == null,
        )
        assertTrue(refusal == null || refusal is DeviceIneligibleException)
    }

    /**
     * Driven onto the refusing side rather than waiting for a handset that refuses.
     *
     * Every device these run on qualifies, so reading the real facts here returned without asserting
     * anything and the redaction this names was never exercised.
     */
    @Test
    fun aRefusalNamesTheCheckAndNotTheDevice() {
        val belowTheFloor = CardReaderEligibility(context, apiLevel = CARD_PRESENT_MIN_API - 1)
        val withoutContactless = CardReaderEligibility(context, hasContactless = { false })

        for (gate in listOf(belowTheFloor, withoutContactless)) {
            val refusal = runCatching { gate.check() }.exceptionOrNull()

            assertTrue("a refusal was expected", refusal is DeviceIneligibleException)
            // The model is what a refusal must not carry: it reaches crash reports and names the handset
            // rather than the check that failed.
            assertTrue(refusal?.message.orEmpty(), !refusal?.message.orEmpty()!!.contains(Build.MODEL))
        }
    }
}
