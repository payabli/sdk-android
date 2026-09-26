package com.payabli.example.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The Simple Capture switch outlives the container that set it, which is what a relaunch builds anew. */
@RunWith(AndroidJUnit4::class)
class SimpleCaptureSettingPersistenceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun turnItOff() {
        AppContainer(context).simpleCapture.setShown(false)
    }

    @Test
    fun aFreshContainerRestoresTheSavedSwitch() {
        AppContainer(context).simpleCapture.setShown(true)
        assertTrue(AppContainer(context).simpleCapture.shown.value)

        AppContainer(context).simpleCapture.setShown(false)
        assertFalse(AppContainer(context).simpleCapture.shown.value)
    }
}
