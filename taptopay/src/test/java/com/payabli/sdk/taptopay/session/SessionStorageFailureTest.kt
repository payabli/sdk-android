package com.payabli.sdk.taptopay.session

import com.payabli.sdk.core.storage.SecureStorageException
import com.payabli.sdk.taptopay.enrollment.FakeSecureStore
import com.payabli.sdk.taptopay.enrollment.RouteScript
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private suspend fun failureOf(block: suspend () -> Unit): Throwable? = runCatching { block() }.exceptionOrNull()

/** Where `initialize` lands when the stored device binding cannot be read. */
class SessionStorageFailureTest {
    @Test
    fun `storage whose key facility cannot answer lands as the device key being unavailable`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture =
                SessionFixture(
                    RouteScript(),
                    storeFailure = FakeSecureStore.failing("get", SecureStorageException.CryptoUnavailable()),
                )
            fixture.seedRecord()

            val failure = failureOf { fixture.coordinator.initialize() }

            assertTrue("$failure", failure is SecureStorageException.CryptoUnavailable)
            assertEquals(
                TapToPaySessionState.Failed(TapToPayFailureReason.DEVICE_KEY_UNAVAILABLE),
                fixture.state,
            )
            assertTrue(fixture.routes.toString(), fixture.routes.isEmpty())
        }

    @Test
    fun `storage that cannot be opened still lands as an internal error`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture =
                SessionFixture(
                    RouteScript(),
                    storeFailure = FakeSecureStore.failing("get", SecureStorageException.StorageUnavailable()),
                )
            fixture.seedRecord()

            failureOf { fixture.coordinator.initialize() }

            assertEquals(TapToPaySessionState.Failed(TapToPayFailureReason.SDK_INTERNAL_ERROR), fixture.state)
        }
}
