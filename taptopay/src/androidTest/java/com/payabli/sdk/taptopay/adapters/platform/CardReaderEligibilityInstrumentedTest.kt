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

    @Test
    fun aRefusalNamesTheCheckAndNotTheDevice() {
        val refusal = runCatching { CardReaderEligibility(context).check() }.exceptionOrNull() ?: return

        assertTrue(refusal.message.orEmpty(), !refusal.message.orEmpty().contains(Build.MODEL))
    }
}
