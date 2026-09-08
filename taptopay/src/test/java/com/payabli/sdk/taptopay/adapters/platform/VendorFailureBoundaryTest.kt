package com.payabli.sdk.taptopay.adapters.platform

import com.fiserv.commercehub.ttp.provider.exception.FiservTTPCardReaderException
import com.payabli.sdk.taptopay.adapters.ReaderFailureKind
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

private const val VENDOR_PACKAGE = "com/fiserv/commercehub/ttp/provider/exception/"

/** A refusal the vendor's own words describe, so anything carrying them out is visible. */
private const val VENDOR_PROSE = "Device has been suspended or deactivated"

/**
 * What the vendor may hand this SDK, and what may leave it.
 *
 * Both claims are about the vendor library rather than about this SDK, which is why they are asserted
 * against the artifact rather than described in a comment: an upgrade is what changes either one.
 */
class VendorFailureBoundaryTest {
    @Test
    fun `every exception the vendor declares descends from the one this SDK catches`() {
        // The gateway has no catch for an unexpected failure, so anything outside this hierarchy would leave
        // the vendor boundary unconverted. Read off the artifact rather than listed here: a type the vendor
        // adds later is in the archive whether or not anyone updates a list.
        val declared = vendorExceptionClasses()
        assertTrue(
            "the vendor's exception package was not found on the test classpath: $declared",
            declared.size >= 8,
        )

        val outside = declared.filterNot { FiservTTPCardReaderException::class.java.isAssignableFrom(it) }

        assertEquals(emptyList<String>(), outside.map { it.name })
    }

    @Test
    fun `a refusal crosses the boundary without the vendor's exception on it`() {
        val vendor = FiservTTPCardReaderException("677", "DeviceDenied", "device", VENDOR_PROSE, "extra")

        val failure = vendor.asFailure(ReaderFailureKind.DEVICE_DENIED)

        // The failure reaches a host as `TapToPayException.cause.cause`, so a vendor type attached here is
        // one a caller can catch and one a crash reporter renders the words of.
        assertNull(failure.cause)
        generateSequence(failure as Throwable) { it.cause }.forEach { link ->
            assertFalse(link.javaClass.name, link.javaClass.name.startsWith("com.fiserv"))
            assertFalse(link.message.orEmpty(), link.message.orEmpty().contains(VENDOR_PROSE))
        }
    }

    @Test
    fun `what the refusal was is kept`() {
        val vendor = FiservTTPCardReaderException("677", "DeviceDenied", "device", VENDOR_PROSE, "extra")

        val failure = vendor.asFailure(ReaderFailureKind.DEVICE_DENIED)

        assertEquals(ReaderFailureKind.DEVICE_DENIED, failure.kind)
        assertEquals(vendor.code, failure.code)
        // Where inside the vendor library it arose, which is the half of a cause worth keeping.
        assertArrayEquals(vendor.stackTrace, failure.stackTrace)
    }

    @Test
    fun `nothing the vendor wrote is readable on the failure a host receives`() {
        // `internal` is a Kotlin boundary and not a JVM one, so every property here compiles to a public
        // getter. A Java caller and a field-inspecting crash reporter read them off the chain whatever the
        // Kotlin surface says, and arming hands the vendor the reader's API credentials, so its prose is
        // text this SDK does not control and cannot vouch for.
        val vendor = FiservTTPCardReaderException("677", "DeviceDenied", "device", VENDOR_PROSE, "extra")

        val failure = vendor.asFailure(ReaderFailureKind.DEVICE_DENIED)

        val readable =
            failure.javaClass.methods
                .filter { it.name.startsWith("get") && it.parameterCount == 0 }
                .mapNotNull { getter -> runCatching { getter.name to getter.invoke(failure) }.getOrNull() }
                .filter { (_, value) -> value is String && value.contains(VENDOR_PROSE) }
                .map { (name, _) -> name }
        assertEquals("the vendor's words are readable through $readable", emptyList<String>(), readable)
    }

    /** Every `Throwable` in the vendor's exception package, read out of the archive it ships in. */
    private fun vendorExceptionClasses(): List<Class<*>> {
        val location =
            FiservTTPCardReaderException::class.java.protectionDomain
                ?.codeSource
                ?.location
                ?: error("the vendor library has no code source, so this test can enumerate nothing")
        val archive = File(location.toURI())
        assertTrue("the vendor library is not an archive: $archive", archive.isFile)

        return ZipFile(archive).use { zip ->
            zip
                .entries()
                .asSequence()
                .map { it.name }
                // No `$` filter: a nested or inner class is exactly where a vendor upgrade adds an
                // exception outside the caught hierarchy, and excluding those left this test claiming a
                // completeness it did not have. The Throwable filter below decides relevance on its own.
                .filter { it.startsWith(VENDOR_PACKAGE) && it.endsWith(".class") }
                .map { Class.forName(it.removeSuffix(".class").replace('/', '.')) }
                .filter { Throwable::class.java.isAssignableFrom(it) }
                .toList()
        }
    }
}
