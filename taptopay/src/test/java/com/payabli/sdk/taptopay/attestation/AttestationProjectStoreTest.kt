package com.payabli.sdk.taptopay.attestation

import com.payabli.sdk.core.config.PayabliEnvironment
import com.payabli.sdk.core.storage.SecureStorageException
import com.payabli.sdk.taptopay.enrollment.FakeSecureStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

private val TEST_TIMEOUT = 5.seconds

class AttestationProjectStoreTest {
    @Test
    fun `a number received on a challenge is kept for a later mint`() =
        runTest(timeout = TEST_TIMEOUT) {
            val store = AttestationProjectStore(FakeSecureStore())

            store.rememberFromChallenge(PayabliEnvironment.SANDBOX, "736636912167")

            assertEquals(736636912167L, store.require(PayabliEnvironment.SANDBOX))
        }

    @Test
    fun `a later challenge carrying a different number replaces the stored one`() =
        runTest(timeout = TEST_TIMEOUT) {
            val store = AttestationProjectStore(FakeSecureStore())

            store.rememberFromChallenge(PayabliEnvironment.SANDBOX, "111")
            store.rememberFromChallenge(PayabliEnvironment.SANDBOX, "222")

            assertEquals(222L, store.require(PayabliEnvironment.SANDBOX))
        }

    @Test
    fun `a number stored for one environment is never used in another`() =
        runTest(timeout = TEST_TIMEOUT) {
            val store = AttestationProjectStore(FakeSecureStore())

            store.remember(PayabliEnvironment.SANDBOX, 111L)
            store.remember(PayabliEnvironment.PRODUCTION, 222L)

            assertEquals(111L, store.numberFor(PayabliEnvironment.SANDBOX))
            assertEquals(222L, store.numberFor(PayabliEnvironment.PRODUCTION))
            store.remember(PayabliEnvironment.SANDBOX, 333L)
            assertEquals(333L, store.require(PayabliEnvironment.SANDBOX))
            assertEquals(222L, store.require(PayabliEnvironment.PRODUCTION))
        }

    @Test
    fun `an environment with nothing stored fails as Misconfigured`() =
        runTest(timeout = TEST_TIMEOUT) {
            val store = AttestationProjectStore(FakeSecureStore())

            store.remember(PayabliEnvironment.SANDBOX, 111L)

            assertNull(store.numberFor(PayabliEnvironment.PRODUCTION))
            val failure =
                runCatching { store.require(PayabliEnvironment.PRODUCTION) }.exceptionOrNull()

            assertTrue(failure is AttestationException.Misconfigured)
            assertNull((failure as AttestationException.Misconfigured).errorCode)
            assertEquals(
                AttestationProjectStore.MISSING_ATTESTATION_PROJECT,
                failure.message,
            )
        }

    @Test
    fun `absent and null challenge fields leave the store unchanged and fail when empty`() =
        runTest(timeout = TEST_TIMEOUT) {
            val store = AttestationProjectStore(FakeSecureStore())

            store.rememberFromChallenge(PayabliEnvironment.SANDBOX, null)
            store.rememberFromChallenge(PayabliEnvironment.SANDBOX, "   ")
            store.rememberFromChallenge(PayabliEnvironment.SANDBOX, "not-a-number")

            assertNull(store.numberFor(PayabliEnvironment.SANDBOX))
            val failure =
                runCatching { store.require(PayabliEnvironment.SANDBOX) }.exceptionOrNull()
                    as AttestationException.Misconfigured

            assertNull(failure.errorCode)
            assertEquals(AttestationProjectStore.MISSING_ATTESTATION_PROJECT, failure.message)
        }

    @Test
    fun `a challenge that omits the number still uses what was stored earlier`() =
        runTest(timeout = TEST_TIMEOUT) {
            val store = AttestationProjectStore(FakeSecureStore())

            store.remember(PayabliEnvironment.SANDBOX, 424242L)
            store.rememberFromChallenge(PayabliEnvironment.SANDBOX, null)

            assertEquals(424242L, store.require(PayabliEnvironment.SANDBOX))
        }

    @Test
    fun `zero and negative wire values are not remembered`() =
        runTest(timeout = TEST_TIMEOUT) {
            val store = AttestationProjectStore(FakeSecureStore())

            store.rememberFromChallenge(PayabliEnvironment.SANDBOX, "0")
            store.rememberFromChallenge(PayabliEnvironment.SANDBOX, "-1")

            assertNull(store.numberFor(PayabliEnvironment.SANDBOX))
        }

    @Test
    fun `a lost or unreadable store entry reads as nothing stored`() =
        runTest(timeout = TEST_TIMEOUT) {
            val lost =
                AttestationProjectStore(
                    FakeSecureStore(FakeSecureStore.failing("get", SecureStorageException.KeyInvalidated())),
                )
            val unreadable =
                AttestationProjectStore(
                    FakeSecureStore(FakeSecureStore.failing("get", SecureStorageException.ValueUnreadable())),
                )

            assertNull(lost.numberFor(PayabliEnvironment.SANDBOX))
            assertNull(unreadable.numberFor(PayabliEnvironment.SANDBOX))
            val failure =
                runCatching { lost.require(PayabliEnvironment.SANDBOX) }.exceptionOrNull()
                    as AttestationException.Misconfigured
            assertEquals(AttestationProjectStore.MISSING_ATTESTATION_PROJECT, failure.message)
        }

    @Test
    fun `a momentarily unavailable store is raised rather than read as no project`() =
        runTest(timeout = TEST_TIMEOUT) {
            val store =
                AttestationProjectStore(
                    FakeSecureStore(
                        FakeSecureStore.failing("get", SecureStorageException.StorageUnavailable()),
                    ),
                )

            val failure = runCatching { store.require(PayabliEnvironment.SANDBOX) }.exceptionOrNull()

            assertTrue(failure is SecureStorageException.StorageUnavailable)
        }
}
