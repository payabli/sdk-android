package com.payabli.sdk.taptopay

import com.payabli.sdk.taptopay.EntryPointHolders.Admission
import com.payabli.sdk.taptopay.model.TapToPayInvoiceData
import com.payabli.sdk.taptopay.model.TapToPayPaymentDetails
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

private const val ENTRY = "paypoint-a"
private const val OTHER_ENTRY = "paypoint-b"

class EntryPointHoldersTest {
    private val holders = EntryPointHolders()

    private fun sale(amount: String = "12.34") =
        ChargeDescription.of(TapToPayPaymentDetails(BigDecimal(amount)), TapToPayInvoiceData())

    /** The claim's signal when the charge was admitted, or null when it was refused. */
    private fun admitted(entry: String = ENTRY): CompletableDeferred<Unit>? =
        (holders.admitCharge(entry, sale()) as? Admission.Admitted)?.inRegion

    @Test
    fun `a close counted after a charge is admitted waits until that charge holds its region`() {
        val charge = admitted()
        assertNotNull("the charge was refused", charge)

        val wait = holders.admitClose(ENTRY)

        assertNotNull("the close was not told about the admitted charge", wait)
        assertFalse("the close may run before the charge locks", wait!!.isCompleted)
        charge!!.complete(Unit)
        assertTrue(wait.isCompleted)
    }

    @Test
    fun `a close with no charge admitted waits for nothing`() {
        assertNull(holders.admitClose(ENTRY))
    }

    @Test
    fun `a close still waits for a charge admitted before another close`() {
        val charge = admitted()!!
        holders.admitClose(ENTRY)

        val wait = holders.admitClose(ENTRY)

        assertNotNull(wait)
        charge.complete(Unit)
        assertTrue(wait!!.isCompleted)
    }

    @Test
    fun `a refused charge is told the sale the running charge describes`() {
        admitted()

        val refusal = holders.admitCharge(ENTRY, sale("99.99")) as Admission.Refused

        assertTrue(refusal.runningSale!!.matches(sale()))
        assertFalse(refusal.runningSale!!.matches(sale("99.99")))
    }

    @Test
    fun `a charge refused by a close alone is told no sale is running`() {
        holders.admitClose(ENTRY)

        val refusal = holders.admitCharge(ENTRY, sale()) as Admission.Refused

        assertNull(refusal.runningSale)
    }

    @Test
    fun `a charge is refused while a close or another charge holds the entry point`() {
        holders.admitClose(ENTRY)
        assertNull("admitted beside a close", admitted())
        holders.release(ENTRY)

        admitted()!!.complete(Unit)
        assertNull("admitted beside a charge", admitted())
    }

    @Test
    fun `an entry point takes a charge again once its last holder releases it`() {
        admitted()!!.complete(Unit)
        holders.admitClose(ENTRY)

        holders.release(ENTRY)
        assertNull("admitted while the close still holds", admitted())
        holders.release(ENTRY)

        assertNotNull(admitted())
    }

    @Test
    fun `a charge on another entry point is admitted while one is held`() {
        admitted()

        assertNotNull(admitted(OTHER_ENTRY))
    }
}
