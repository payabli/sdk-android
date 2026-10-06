package com.payabli.sdk.payin.form

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** The summary's labels, read through resources as the form reads them. */
@RunWith(AndroidJUnit4::class)
class PayInSummaryRowLabelsInstrumentedTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun eachMoneyRowReadsItsResourceWording() {
        val labels = PayInFormLabels()

        assertEquals("Amount", PayInSummaryRows.labelText(PayInField.Amount, labels, context))
        assertEquals("Fee", PayInSummaryRows.labelText(PayInField.ServiceFee, labels, context))
        assertEquals("Surcharge", PayInSummaryRows.labelText(PayInField.SurchargeFee, labels, context))
        assertEquals("Total", PayInSummaryRows.totalLabelText(labels, context))
    }

    @Test
    fun aCallersWordingWins() {
        val labels =
            PayInFormLabels(
                fieldLabels = mapOf(PayInField.ServiceFee to "Convenience fee"),
                total = "Due today",
            )

        assertEquals("Convenience fee", PayInSummaryRows.labelText(PayInField.ServiceFee, labels, context))
        assertEquals("Due today", PayInSummaryRows.totalLabelText(labels, context))
    }

    @Test
    fun blankWordingFallsBackToTheResource() {
        val labels =
            PayInFormLabels(
                fieldLabels = mapOf(PayInField.Amount to " "),
                total = "",
            )

        assertEquals("Amount", PayInSummaryRows.labelText(PayInField.Amount, labels, context))
        assertEquals("Total", PayInSummaryRows.totalLabelText(labels, context))
    }
}
