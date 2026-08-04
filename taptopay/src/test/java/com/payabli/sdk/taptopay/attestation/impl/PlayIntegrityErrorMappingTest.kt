package com.payabli.sdk.taptopay.attestation.impl

import com.google.android.play.core.integrity.model.IntegrityErrorCode
import com.google.android.play.core.integrity.model.StandardIntegrityErrorCode
import com.payabli.sdk.taptopay.attestation.AttestationException
import com.payabli.sdk.taptopay.attestation.VerdictClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Every failure constant the platform documents for a standard request, and every one for a classic
// request. Written out rather than reflected over the annotation types: the point of the completeness
// test below is that a human wrote down what the set is, so a constant appearing in a library update
// has to be read and placed rather than absorbed silently.
private val ALL_STANDARD_CODES =
    listOf(
        StandardIntegrityErrorCode.API_NOT_AVAILABLE,
        StandardIntegrityErrorCode.PLAY_STORE_NOT_FOUND,
        StandardIntegrityErrorCode.NETWORK_ERROR,
        StandardIntegrityErrorCode.APP_NOT_INSTALLED,
        StandardIntegrityErrorCode.PLAY_SERVICES_NOT_FOUND,
        StandardIntegrityErrorCode.APP_UID_MISMATCH,
        StandardIntegrityErrorCode.TOO_MANY_REQUESTS,
        StandardIntegrityErrorCode.CANNOT_BIND_TO_SERVICE,
        StandardIntegrityErrorCode.GOOGLE_SERVER_UNAVAILABLE,
        StandardIntegrityErrorCode.PLAY_STORE_VERSION_OUTDATED,
        StandardIntegrityErrorCode.PLAY_SERVICES_VERSION_OUTDATED,
        StandardIntegrityErrorCode.CLOUD_PROJECT_NUMBER_IS_INVALID,
        StandardIntegrityErrorCode.REQUEST_HASH_TOO_LONG,
        StandardIntegrityErrorCode.CLIENT_TRANSIENT_ERROR,
        StandardIntegrityErrorCode.INTEGRITY_TOKEN_PROVIDER_INVALID,
        StandardIntegrityErrorCode.INTERNAL_ERROR,
    )

private val ALL_CLASSIC_CODES =
    listOf(
        IntegrityErrorCode.API_NOT_AVAILABLE,
        IntegrityErrorCode.PLAY_STORE_NOT_FOUND,
        IntegrityErrorCode.NETWORK_ERROR,
        IntegrityErrorCode.PLAY_STORE_ACCOUNT_NOT_FOUND,
        IntegrityErrorCode.APP_NOT_INSTALLED,
        IntegrityErrorCode.PLAY_SERVICES_NOT_FOUND,
        IntegrityErrorCode.APP_UID_MISMATCH,
        IntegrityErrorCode.TOO_MANY_REQUESTS,
        IntegrityErrorCode.CANNOT_BIND_TO_SERVICE,
        IntegrityErrorCode.NONCE_TOO_SHORT,
        IntegrityErrorCode.NONCE_TOO_LONG,
        IntegrityErrorCode.GOOGLE_SERVER_UNAVAILABLE,
        IntegrityErrorCode.NONCE_IS_NOT_BASE64,
        IntegrityErrorCode.PLAY_STORE_VERSION_OUTDATED,
        IntegrityErrorCode.PLAY_SERVICES_VERSION_OUTDATED,
        IntegrityErrorCode.CLOUD_PROJECT_NUMBER_IS_INVALID,
        IntegrityErrorCode.CLIENT_TRANSIENT_ERROR,
        IntegrityErrorCode.INTERNAL_ERROR,
    )

/**
 * Both error enums, every documented constant, against an explicit table.
 *
 * The expectations are written out rather than derived from the mapper, on purpose: an expectation derived
 * from the code under test asserts only that the code equals itself. Written out, a mapping changed by
 * accident has to be changed here too, by someone who then has to agree with it.
 */
class PlayIntegrityErrorMappingTest {
    private val standardRetryable =
        listOf(
            StandardIntegrityErrorCode.NETWORK_ERROR,
            StandardIntegrityErrorCode.TOO_MANY_REQUESTS,
            StandardIntegrityErrorCode.GOOGLE_SERVER_UNAVAILABLE,
            StandardIntegrityErrorCode.CLIENT_TRANSIENT_ERROR,
            StandardIntegrityErrorCode.INTERNAL_ERROR,
            // Only reaches the mapper once a fresh provider was already prepared and rejected too.
            StandardIntegrityErrorCode.INTEGRITY_TOKEN_PROVIDER_INVALID,
        )

    private val standardRemediation =
        listOf(
            StandardIntegrityErrorCode.API_NOT_AVAILABLE,
            StandardIntegrityErrorCode.PLAY_STORE_NOT_FOUND,
            StandardIntegrityErrorCode.PLAY_SERVICES_NOT_FOUND,
            StandardIntegrityErrorCode.CANNOT_BIND_TO_SERVICE,
            StandardIntegrityErrorCode.PLAY_STORE_VERSION_OUTDATED,
            StandardIntegrityErrorCode.PLAY_SERVICES_VERSION_OUTDATED,
        )

    private val standardFailed =
        listOf(
            StandardIntegrityErrorCode.APP_NOT_INSTALLED,
            StandardIntegrityErrorCode.APP_UID_MISMATCH,
        )

    private val standardMisconfigured =
        listOf(
            StandardIntegrityErrorCode.CLOUD_PROJECT_NUMBER_IS_INVALID,
            StandardIntegrityErrorCode.REQUEST_HASH_TOO_LONG,
        )

    private val classicRetryable =
        listOf(
            IntegrityErrorCode.NETWORK_ERROR,
            IntegrityErrorCode.TOO_MANY_REQUESTS,
            IntegrityErrorCode.GOOGLE_SERVER_UNAVAILABLE,
            IntegrityErrorCode.CLIENT_TRANSIENT_ERROR,
            IntegrityErrorCode.INTERNAL_ERROR,
        )

    private val classicRemediation =
        listOf(
            IntegrityErrorCode.API_NOT_AVAILABLE,
            IntegrityErrorCode.PLAY_STORE_NOT_FOUND,
            IntegrityErrorCode.PLAY_STORE_ACCOUNT_NOT_FOUND,
            IntegrityErrorCode.PLAY_SERVICES_NOT_FOUND,
            IntegrityErrorCode.CANNOT_BIND_TO_SERVICE,
            IntegrityErrorCode.PLAY_STORE_VERSION_OUTDATED,
            IntegrityErrorCode.PLAY_SERVICES_VERSION_OUTDATED,
        )

    private val classicFailed =
        listOf(
            IntegrityErrorCode.APP_NOT_INSTALLED,
            IntegrityErrorCode.APP_UID_MISMATCH,
        )

    private val classicMisconfigured =
        listOf(
            IntegrityErrorCode.CLOUD_PROJECT_NUMBER_IS_INVALID,
            IntegrityErrorCode.NONCE_TOO_SHORT,
            IntegrityErrorCode.NONCE_TOO_LONG,
            IntegrityErrorCode.NONCE_IS_NOT_BASE64,
        )

    private fun standard(code: Int) = PlayIntegrityErrorMapping.failureFor(code, VerdictClass.STANDARD)

    private fun classic(code: Int) = PlayIntegrityErrorMapping.failureFor(code, VerdictClass.CLASSIC)

    private inline fun <reified T : AttestationException> assertAllMap(
        codes: List<Int>,
        map: (Int) -> AttestationException,
    ) {
        codes.forEach { code ->
            val actual = map(code)
            assertTrue(
                "expected ${T::class.java.simpleName} for code $code, got ${actual::class.java.simpleName}",
                actual is T,
            )
            assertEquals("the code must survive the mapping", code, actual.errorCode)
        }
    }

    // --- standard -----------------------------------------------------------------------------------

    @Test
    fun `standard transient codes are retryable`() =
        assertAllMap<AttestationException.Retryable>(standardRetryable, ::standard)

    @Test
    fun `standard Play Store and services codes need remediation`() =
        assertAllMap<AttestationException.RemediationRequired>(standardRemediation, ::standard)

    @Test
    fun `standard non-actionable codes are a failed integrity check`() =
        assertAllMap<AttestationException.IntegrityFailed>(standardFailed, ::standard)

    @Test
    fun `standard configuration codes are our own misconfiguration`() =
        assertAllMap<AttestationException.Misconfigured>(standardMisconfigured, ::standard)

    @Test
    fun `every documented standard code is classified exactly once`() {
        val classified = standardRetryable + standardRemediation + standardFailed + standardMisconfigured

        assertEquals("a code classified twice", classified.size, classified.toSet().size)
        assertEquals(
            "a documented code with no disposition, or a disposition for a code that is not documented",
            ALL_STANDARD_CODES.toSet(),
            classified.toSet(),
        )
    }

    // --- classic ------------------------------------------------------------------------------------

    @Test
    fun `classic transient codes are retryable`() =
        assertAllMap<AttestationException.Retryable>(classicRetryable, ::classic)

    @Test
    fun `classic Play Store and services codes need remediation`() =
        assertAllMap<AttestationException.RemediationRequired>(classicRemediation, ::classic)

    @Test
    fun `classic non-actionable codes are a failed integrity check`() =
        assertAllMap<AttestationException.IntegrityFailed>(classicFailed, ::classic)

    @Test
    fun `classic nonce and project codes are our own misconfiguration`() =
        assertAllMap<AttestationException.Misconfigured>(classicMisconfigured, ::classic)

    @Test
    fun `every documented classic code is classified exactly once`() {
        val classified = classicRetryable + classicRemediation + classicFailed + classicMisconfigured

        assertEquals("a code classified twice", classified.size, classified.toSet().size)
        assertEquals(
            "a documented code with no disposition, or a disposition for a code that is not documented",
            ALL_CLASSIC_CODES.toSet(),
            classified.toSet(),
        )
    }

    // --- the collision, and the unknown -------------------------------------------------------------

    @Test
    fun `code -17 means different things in the two classes`() {
        // This is the entire reason there are two tables. CLIENT_TRANSIENT_ERROR for a classic request,
        // REQUEST_HASH_TOO_LONG for a standard one: "wait" against "we built the request wrong". One
        // shared table would be wrong for one of them, silently, forever.
        assertEquals(-17, IntegrityErrorCode.CLIENT_TRANSIENT_ERROR)
        assertEquals(-17, StandardIntegrityErrorCode.REQUEST_HASH_TOO_LONG)

        assertTrue(classic(-17) is AttestationException.Retryable)
        assertTrue(standard(-17) is AttestationException.Misconfigured)
    }

    @Test
    fun `an unrecognised code is retryable rather than a verdict`() {
        // A code the platform adds later is far more likely to be a new transient condition than a new
        // verdict, and calling the unknown a failed integrity check would turn that addition into a
        // decline. Retrying can never mint a token that was not issued, so nothing is weakened.
        assertTrue(standard(-9999) is AttestationException.Retryable)
        assertTrue(classic(-9999) is AttestationException.Retryable)
        assertEquals(-9999, standard(-9999).errorCode)
    }

    @Test
    fun `a failure with no code at all is retryable`() {
        val standardFailure = PlayIntegrityErrorMapping.failureFor(null, VerdictClass.STANDARD)
        val classicFailure = PlayIntegrityErrorMapping.failureFor(null, VerdictClass.CLASSIC)

        assertTrue(standardFailure is AttestationException.Retryable)
        assertTrue(classicFailure is AttestationException.Retryable)
        assertEquals(null, standardFailure.errorCode)
        assertEquals(null, classicFailure.errorCode)
    }

    @Test
    fun `the platform's no-error value is not treated as a classification`() {
        // NO_ERROR arriving as a failure is the platform contradicting itself. It must not land in any
        // table, because every table entry is a claim about what went wrong.
        assertTrue(standard(StandardIntegrityErrorCode.NO_ERROR) is AttestationException.Retryable)
        assertTrue(classic(IntegrityErrorCode.NO_ERROR) is AttestationException.Retryable)
    }

    // --- disclosure ---------------------------------------------------------------------------------

    @Test
    fun `the cause is carried and the rendering names only the classification`() {
        val cause = IntegrityFailure(IntegrityErrorCode.NETWORK_ERROR)
        val mapped =
            PlayIntegrityErrorMapping.failureFor(IntegrityErrorCode.NETWORK_ERROR, VerdictClass.CLASSIC, cause)

        assertEquals(cause, mapped.cause)
        assertEquals("Retryable(errorCode=-3)", mapped.toString())
    }
}
