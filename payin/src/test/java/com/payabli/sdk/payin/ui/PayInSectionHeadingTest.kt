package com.payabli.sdk.payin.ui

import com.payabli.sdk.payin.form.PayInField
import com.payabli.sdk.payin.form.PayInFormSection
import com.payabli.sdk.payin.form.PayInSectionStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A blank host title counts as not given, and a given one is drawn as written. */
class PayInSectionHeadingTest {
    @Test
    fun `a blank summary title reads the default`() {
        assertEquals(DEFAULT, summary("   ").heading(DEFAULT))
    }

    @Test
    fun `a blank inputs title draws no heading`() {
        assertNull(inputs(" \t ").heading(DEFAULT))
    }

    @Test
    fun `a given title is drawn as written`() {
        assertEquals(" Checkout ", summary(" Checkout ").heading(DEFAULT))
        assertEquals(" Card ", inputs(" Card ").heading(DEFAULT))
    }

    @Test
    fun `no title reads the default on a summary and nothing on inputs`() {
        assertEquals(DEFAULT, summary(null).heading(DEFAULT))
        assertNull(inputs(null).heading(DEFAULT))
    }

    private fun summary(title: String?) =
        PayInFormSection(listOf(PayInField.Amount), title = title, style = PayInSectionStyle.Summary)

    private fun inputs(title: String?) = PayInFormSection(listOf(PayInField.CardNumber), title = title)

    private companion object {
        const val DEFAULT = "Payment"
    }
}
