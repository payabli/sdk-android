package com.payabli.example.app.demo.qa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoCustomerSettingTest {
    private val identity = QaIdentity.from("Google Pixel 7a")

    @Test
    fun `a capture names its customer unless someone says otherwise`() {
        // The default is the load-bearing half: a run that forgets to look at the switch still lands every
        // payment on one customer, which is what makes a dashboard readable.
        assertTrue(DemoCustomerSetting(identity).suppliesDemoCustomer.value)
    }

    @Test
    fun `the switch is what decides it`() {
        val setting = DemoCustomerSetting(identity)

        setting.setSuppliesDemoCustomer(false)
        assertEquals(false, setting.suppliesDemoCustomer.value)

        setting.setSuppliesDemoCustomer(true)
        assertEquals(true, setting.suppliesDemoCustomer.value)
    }

    @Test
    fun `the summary says who would be sent`() {
        // Read back on the Configuration screen under the switch, so it names the customer the request
        // carries.
        val summary = DemoCustomerSetting(identity).summary

        assertTrue(summary, summary.contains(identity.holderName))
        assertTrue(summary, summary.contains(identity.customerNumber))
    }
}
