package com.payabli.example.app.demo.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticsStoreTest {
    @Test
    fun `starts empty`() {
        assertEquals(emptyList<String>(), DiagnosticsStore().messages.value)
    }

    @Test
    fun `keeps entries oldest first`() {
        val store = DiagnosticsStore()
        store.record("first")
        store.record("second")
        store.record("third")
        assertEquals(listOf("first", "second", "third"), store.messages.value)
    }

    @Test
    fun `holds exactly the limit without dropping anything`() {
        val store = DiagnosticsStore(limit = 3)
        repeat(3) { store.record("entry $it") }
        assertEquals(listOf("entry 0", "entry 1", "entry 2"), store.messages.value)
    }

    @Test
    fun `drops the oldest once past the limit`() {
        val store = DiagnosticsStore(limit = 3)
        repeat(5) { store.record("entry $it") }
        assertEquals(listOf("entry 2", "entry 3", "entry 4"), store.messages.value)
    }

    @Test
    fun `never grows past the limit however many arrive`() {
        val store = DiagnosticsStore(limit = 3)
        repeat(100) { store.record("entry $it") }
        assertEquals(3, store.messages.value.size)
        assertEquals("entry 99", store.messages.value.last())
    }

    @Test
    fun `clear empties it`() {
        val store = DiagnosticsStore()
        store.record("something")
        store.clear()
        assertEquals(emptyList<String>(), store.messages.value)
    }

    @Test
    fun `the two registry stores do not share entries`() {
        val registry = DiagnosticsRegistry()
        registry.paymentMethod.record("method traffic")
        assertEquals(listOf("method traffic"), registry.paymentMethod.messages.value)
        assertEquals(emptyList<String>(), registry.capture.messages.value)
    }
}
