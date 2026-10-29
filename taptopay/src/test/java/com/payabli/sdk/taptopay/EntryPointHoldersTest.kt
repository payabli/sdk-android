package com.payabli.sdk.taptopay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val ENTRY = "paypoint-a"
private const val OTHER_ENTRY = "paypoint-b"

class EntryPointHoldersTest {
    private val holders = EntryPointHolders()

    @Test
    fun `a close counted after a charge is admitted waits until that charge holds its region`() {
        val charge = holders.admitCharge(ENTRY)
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
        val charge = holders.admitCharge(ENTRY)!!
        holders.admitClose(ENTRY)

        val wait = holders.admitClose(ENTRY)

        assertNotNull(wait)
        charge.complete(Unit)
        assertTrue(wait!!.isCompleted)
    }

    @Test
    fun `a charge is refused while a close or another charge holds the entry point`() {
        holders.admitClose(ENTRY)
        assertNull("admitted beside a close", holders.admitCharge(ENTRY))
        holders.release(ENTRY)

        holders.admitCharge(ENTRY)!!.complete(Unit)
        assertNull("admitted beside a charge", holders.admitCharge(ENTRY))
    }

    @Test
    fun `an entry point takes a charge again once its last holder releases it`() {
        holders.admitCharge(ENTRY)!!.complete(Unit)
        holders.admitClose(ENTRY)

        holders.release(ENTRY)
        assertNull("admitted while the close still holds", holders.admitCharge(ENTRY))
        holders.release(ENTRY)

        assertNotNull(holders.admitCharge(ENTRY))
    }

    @Test
    fun `a charge on another entry point is admitted while one is held`() {
        holders.admitCharge(ENTRY)

        assertNotNull(holders.admitCharge(OTHER_ENTRY))
    }
}
