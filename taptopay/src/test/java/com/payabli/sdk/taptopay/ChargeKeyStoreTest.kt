package com.payabli.sdk.taptopay

import com.payabli.sdk.core.config.PayabliEnvironment
import com.payabli.sdk.core.network.IDEMPOTENCY_KEY_MAX_AGE
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private const val ENTRY = "paypoint-a"
private const val OTHER_ENTRY = "paypoint-b"

class ChargeKeyStoreTest {
    private val clock = AtomicLong(1_000_000_000L)
    private val minted = AtomicInteger()

    @After
    fun forgetHeld() = ChargeKeyStore.forgetHeld()

    private fun store(): ChargeKeyStore =
        ChargeKeyStore(
            newKey = { "key-${minted.incrementAndGet()}" },
            elapsedRealtimeNanos = clock::get,
        )

    @Test
    fun `an unsettled charge keeps the key it reserved`() =
        runBlocking {
            val keys = store()
            assertEquals("key-1", keys.reserve(ENTRY, PayabliEnvironment.SANDBOX).key)
            assertEquals("key-1", keys.reserve(ENTRY, PayabliEnvironment.SANDBOX).key)
        }

    @Test
    fun `a second reservation of the same attempt is reported as reused`() =
        runBlocking {
            val keys = store()
            assertFalse(keys.reserve(ENTRY, PayabliEnvironment.SANDBOX).reused)
            assertTrue(keys.reserve(ENTRY, PayabliEnvironment.SANDBOX).reused)
        }

    @Test
    fun `a settled charge leaves the next one to reserve its own`() =
        runBlocking {
            val keys = store()
            val first = keys.reserve(ENTRY, PayabliEnvironment.SANDBOX).key
            keys.settle(ENTRY, PayabliEnvironment.SANDBOX, first)
            assertEquals("key-2", keys.reserve(ENTRY, PayabliEnvironment.SANDBOX).key)
        }

    @Test
    fun `a settle whose key no longer matches leaves the held attempt alone`() =
        runBlocking {
            val keys = store()
            val stale = keys.reserve(ENTRY, PayabliEnvironment.SANDBOX).key
            keys.settle(ENTRY, PayabliEnvironment.SANDBOX, stale)
            val current = keys.reserve(ENTRY, PayabliEnvironment.SANDBOX).key
            keys.settle(ENTRY, PayabliEnvironment.SANDBOX, stale)
            assertEquals(
                "the in-flight attempt was dropped by a stale settle",
                current,
                keys.reserve(ENTRY, PayabliEnvironment.SANDBOX).key,
            )
        }

    @Test
    fun `two stores over the process map mint one key under one lock`() =
        runBlocking {
            val first = store()
            val second = store()
            val one = async { first.reserve(ENTRY, PayabliEnvironment.SANDBOX).key }
            val other = async { second.reserve(ENTRY, PayabliEnvironment.SANDBOX).key }
            assertEquals(one.await(), other.await())
        }

    @Test
    fun `two entry points hold independent keys`() =
        runBlocking {
            val keys = store()
            val one = keys.reserve(ENTRY, PayabliEnvironment.SANDBOX).key
            val other = keys.reserve(OTHER_ENTRY, PayabliEnvironment.SANDBOX).key
            assertNotEquals(one, other)
            assertEquals(one, keys.reserve(ENTRY, PayabliEnvironment.SANDBOX).key)
        }

    @Test
    fun `settling one entry point leaves the other held`() =
        runBlocking {
            val keys = store()
            val one = keys.reserve(ENTRY, PayabliEnvironment.SANDBOX).key
            val other = keys.reserve(OTHER_ENTRY, PayabliEnvironment.SANDBOX).key
            keys.settle(ENTRY, PayabliEnvironment.SANDBOX, one)
            assertEquals(other, keys.reserve(OTHER_ENTRY, PayabliEnvironment.SANDBOX).key)
            assertNotEquals(one, keys.reserve(ENTRY, PayabliEnvironment.SANDBOX).key)
        }

    @Test
    fun `a sandbox attempt is not handed to production for the same entry point`() =
        runBlocking {
            val keys = store()
            val sandbox = keys.reserve(ENTRY, PayabliEnvironment.SANDBOX).key
            val production = keys.reserve(ENTRY, PayabliEnvironment.PRODUCTION).key
            assertNotEquals(sandbox, production)
            keys.settle(ENTRY, PayabliEnvironment.PRODUCTION, production)
            assertEquals(sandbox, keys.reserve(ENTRY, PayabliEnvironment.SANDBOX).key)
        }

    @Test
    fun `an expired key is not resent`() =
        runBlocking {
            val keys = store()
            assertEquals("key-1", keys.reserve(ENTRY, PayabliEnvironment.SANDBOX).key)
            clock.addAndGet(IDEMPOTENCY_KEY_MAX_AGE.inWholeNanoseconds)
            assertEquals("key-2", keys.reserve(ENTRY, PayabliEnvironment.SANDBOX).key)
            assertFalse(keys.reserve(ENTRY, PayabliEnvironment.SANDBOX).key == "key-1")
        }

    @Test
    fun `an arrived key is not swept past the bound`() =
        runBlocking {
            val keys = store()
            val reserved = keys.reserve(ENTRY, PayabliEnvironment.SANDBOX)
            keys.markArrived(ENTRY, PayabliEnvironment.SANDBOX, reserved.key)
            clock.addAndGet(IDEMPOTENCY_KEY_MAX_AGE.inWholeNanoseconds * 2)
            assertEquals(reserved.key, keys.reserve(ENTRY, PayabliEnvironment.SANDBOX).key)
            assertTrue(keys.reserve(ENTRY, PayabliEnvironment.SANDBOX).reused)
        }

    @Test
    fun `a wall clock is never consulted so advancing it does not drop a live key`() =
        runBlocking {
            // The store only reads [elapsedRealtimeNanos]. Holding the monotonic clock still and
            // advancing a wall clock elsewhere cannot expire the key — there is no wall clock to move.
            val keys = store()
            assertEquals("key-1", keys.reserve(ENTRY, PayabliEnvironment.SANDBOX).key)
            assertEquals("key-1", keys.reserve(ENTRY, PayabliEnvironment.SANDBOX).key)
        }

    @Test
    fun `a full store refuses rather than evicting a live attempt`() =
        runBlocking {
            val keys = store()
            repeat(ChargeKeyStore.MAX) { keys.reserve("entry-$it", PayabliEnvironment.SANDBOX) }
            val refused = runCatching { keys.reserve("entry-overflow", PayabliEnvironment.SANDBOX) }.exceptionOrNull()
            assertTrue("$refused", refused is ChargeKeyStoreFullException)
            assertEquals(
                "an oldest unresolved attempt was evicted",
                "key-1",
                keys.reserve("entry-0", PayabliEnvironment.SANDBOX).key,
            )
        }

    @Test
    fun `forgetting held keys empties the process map`() =
        runBlocking {
            val keys = store()
            keys.reserve(ENTRY, PayabliEnvironment.SANDBOX)
            ChargeKeyStore.forgetHeld()
            assertFalse(keys.reserve(ENTRY, PayabliEnvironment.SANDBOX).reused)
            assertEquals("key-2", keys.reserve(ENTRY, PayabliEnvironment.SANDBOX).key)
        }
}
