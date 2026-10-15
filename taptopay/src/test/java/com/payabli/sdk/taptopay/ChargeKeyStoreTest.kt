package com.payabli.sdk.taptopay

import com.payabli.sdk.core.config.PayabliEnvironment
import com.payabli.sdk.core.model.PayabliAttempt
import com.payabli.sdk.core.network.IDEMPOTENCY_KEY_MAX_AGE
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private const val ENTRY = "paypoint-a"
private const val OTHER_ENTRY = "paypoint-b"

class ChargeKeyStoreTest {
    private val clock = AtomicLong(1_000_000_000L)
    private val minted = AtomicInteger()

    @Before
    @After
    fun forgetHeld() = ChargeKeyStore.forgetHeld()

    private fun store(): ChargeKeyStore =
        ChargeKeyStore(
            newKey = { "key-${minted.incrementAndGet()}" },
            elapsedRealtimeNanos = clock::get,
        )

    /** A sale of its own is not a repeat, so it never takes the key an unsettled charge is holding. */
    @Test
    fun `an ordinary charge mints its own key while one is held`() =
        runBlocking {
            val keys = store()
            keys.reserve(ENTRY, PayabliEnvironment.SANDBOX, PayabliAttempt.NewPayment)

            val ordinary = keys.reserve(ENTRY, PayabliEnvironment.SANDBOX, PayabliAttempt.NewPayment)

            assertEquals("key-2", ordinary.key)
            assertFalse(ordinary.reused)
        }

    @Test
    fun `an ordinary charge that settles leaves the held key for a declared retry`() =
        runBlocking {
            val keys = store()
            keys.reserve(ENTRY, PayabliEnvironment.SANDBOX, PayabliAttempt.NewPayment)
            val ordinary = keys.reserve(ENTRY, PayabliEnvironment.SANDBOX, PayabliAttempt.NewPayment)
            keys.settle(ENTRY, PayabliEnvironment.SANDBOX, ordinary.key)

            val retry = keys.reserve(ENTRY, PayabliEnvironment.SANDBOX, PayabliAttempt.Retry)

            assertEquals("key-1", retry.key)
            assertTrue(retry.reused)
        }

    /** Two charges left unsettled: a retry is of the latest, the one the host just saw fail. */
    @Test
    fun `a declared retry claims the newest held key`() =
        runBlocking {
            val keys = store()
            keys.reserve(ENTRY, PayabliEnvironment.SANDBOX, PayabliAttempt.NewPayment)
            keys.reserve(ENTRY, PayabliEnvironment.SANDBOX, PayabliAttempt.NewPayment)

            assertEquals("key-2", keys.reserve(ENTRY, PayabliEnvironment.SANDBOX, PayabliAttempt.Retry).key)
        }

    @Test
    fun `a declared retry with nothing held mints`() =
        runBlocking {
            val retry = store().reserve(ENTRY, PayabliEnvironment.SANDBOX, PayabliAttempt.Retry)

            assertEquals("key-1", retry.key)
            assertFalse(retry.reused)
        }

    @Test
    fun `an unsettled charge keeps the key it reserved`() =
        runBlocking {
            val keys = store()
            assertEquals("key-1", keys.reserve(ENTRY, PayabliEnvironment.SANDBOX, PayabliAttempt.Retry).key)
            assertEquals("key-1", keys.reserve(ENTRY, PayabliEnvironment.SANDBOX, PayabliAttempt.Retry).key)
        }

    @Test
    fun `a second reservation of the same attempt is reported as reused`() =
        runBlocking {
            val keys = store()
            assertFalse(keys.reserve(ENTRY, PayabliEnvironment.SANDBOX, PayabliAttempt.Retry).reused)
            assertTrue(keys.reserve(ENTRY, PayabliEnvironment.SANDBOX, PayabliAttempt.Retry).reused)
        }

    @Test
    fun `a settled charge leaves the next one to reserve its own`() =
        runBlocking {
            val keys = store()
            val first = keys.reserve(ENTRY, PayabliEnvironment.SANDBOX, PayabliAttempt.Retry).key
            keys.settle(ENTRY, PayabliEnvironment.SANDBOX, first)
            assertEquals("key-2", keys.reserve(ENTRY, PayabliEnvironment.SANDBOX, PayabliAttempt.Retry).key)
        }

    @Test
    fun `a settle whose key no longer matches leaves the held attempt alone`() =
        runBlocking {
            val keys = store()
            val stale = keys.reserve(ENTRY, PayabliEnvironment.SANDBOX, PayabliAttempt.Retry).key
            keys.settle(ENTRY, PayabliEnvironment.SANDBOX, stale)
            val current = keys.reserve(ENTRY, PayabliEnvironment.SANDBOX, PayabliAttempt.Retry).key
            keys.settle(ENTRY, PayabliEnvironment.SANDBOX, stale)
            assertEquals(
                "the in-flight attempt was dropped by a stale settle",
                current,
                keys.reserve(ENTRY, PayabliEnvironment.SANDBOX, PayabliAttempt.Retry).key,
            )
        }

    @Test
    fun `two stores over the process map mint one key under one lock`() =
        runBlocking {
            // Park the first mint under the lock so a second reserve races the lookup. Without the shared
            // mutex both would mint; with it the second waits and reuses.
            val mintStarted = CompletableDeferred<Unit>()
            val releaseMint = CompletableDeferred<Unit>()
            val racing = AtomicInteger()
            val first =
                ChargeKeyStore(
                    newKey = {
                        val n = racing.incrementAndGet()
                        if (n == 1) {
                            mintStarted.complete(Unit)
                            runBlocking { releaseMint.await() }
                        }
                        "key-$n"
                    },
                    elapsedRealtimeNanos = clock::get,
                )
            val second =
                ChargeKeyStore(
                    newKey = {
                        val n = racing.incrementAndGet()
                        "key-$n"
                    },
                    elapsedRealtimeNanos = clock::get,
                )

            val one = async { first.reserve(ENTRY, PayabliEnvironment.SANDBOX, PayabliAttempt.Retry).key }
            mintStarted.await()
            val other = async { second.reserve(ENTRY, PayabliEnvironment.SANDBOX, PayabliAttempt.Retry).key }
            yield()
            releaseMint.complete(Unit)

            assertEquals(one.await(), other.await())
            assertEquals("two keys were minted under contention", 1, racing.get())
        }

    @Test
    fun `two stores over the process map share one held key`() =
        runBlocking {
            // Two store objects, one companion map: what two terminals for one entry point look like.
            val first = store()
            val second = store()
            assertEquals(
                first.reserve(ENTRY, PayabliEnvironment.SANDBOX, PayabliAttempt.Retry).key,
                second.reserve(ENTRY, PayabliEnvironment.SANDBOX, PayabliAttempt.Retry).key,
            )
            assertTrue(second.reserve(ENTRY, PayabliEnvironment.SANDBOX, PayabliAttempt.Retry).reused)
        }

    @Test
    fun `two entry points hold independent keys`() =
        runBlocking {
            val keys = store()
            val one = keys.reserve(ENTRY, PayabliEnvironment.SANDBOX, PayabliAttempt.Retry).key
            val other = keys.reserve(OTHER_ENTRY, PayabliEnvironment.SANDBOX, PayabliAttempt.Retry).key
            assertNotEquals(one, other)
            assertEquals(one, keys.reserve(ENTRY, PayabliEnvironment.SANDBOX, PayabliAttempt.Retry).key)
        }

    @Test
    fun `settling one entry point leaves the other held`() =
        runBlocking {
            val keys = store()
            val one = keys.reserve(ENTRY, PayabliEnvironment.SANDBOX, PayabliAttempt.Retry).key
            val other = keys.reserve(OTHER_ENTRY, PayabliEnvironment.SANDBOX, PayabliAttempt.Retry).key
            keys.settle(ENTRY, PayabliEnvironment.SANDBOX, one)
            assertEquals(other, keys.reserve(OTHER_ENTRY, PayabliEnvironment.SANDBOX, PayabliAttempt.Retry).key)
            assertNotEquals(one, keys.reserve(ENTRY, PayabliEnvironment.SANDBOX, PayabliAttempt.Retry).key)
        }

    @Test
    fun `a sandbox attempt is not handed to production for the same entry point`() =
        runBlocking {
            val keys = store()
            val sandbox = keys.reserve(ENTRY, PayabliEnvironment.SANDBOX, PayabliAttempt.Retry).key
            val production = keys.reserve(ENTRY, PayabliEnvironment.PRODUCTION, PayabliAttempt.Retry).key
            assertNotEquals(sandbox, production)
            keys.settle(ENTRY, PayabliEnvironment.PRODUCTION, production)
            assertEquals(sandbox, keys.reserve(ENTRY, PayabliEnvironment.SANDBOX, PayabliAttempt.Retry).key)
        }

    @Test
    fun `an expired key is not resent`() =
        runBlocking {
            val keys = store()
            assertEquals("key-1", keys.reserve(ENTRY, PayabliEnvironment.SANDBOX, PayabliAttempt.Retry).key)
            clock.addAndGet(IDEMPOTENCY_KEY_MAX_AGE.inWholeNanoseconds)
            assertEquals("key-2", keys.reserve(ENTRY, PayabliEnvironment.SANDBOX, PayabliAttempt.Retry).key)
            assertFalse(keys.reserve(ENTRY, PayabliEnvironment.SANDBOX, PayabliAttempt.Retry).key == "key-1")
        }

    /** A retry only ever claims the newest key, so an older one for the same entry point only takes room. */
    @Test
    fun `a charge that ends unknown drops the older key held for its entry point`() =
        runBlocking {
            val keys = store()
            keys.reserve(ENTRY, PayabliEnvironment.SANDBOX, PayabliAttempt.NewPayment)
            val newer = keys.reserve(ENTRY, PayabliEnvironment.SANDBOX, PayabliAttempt.NewPayment)
            keys.supersede(ENTRY, PayabliEnvironment.SANDBOX, newer.key)
            keys.settle(ENTRY, PayabliEnvironment.SANDBOX, newer.key)

            assertEquals("key-3", keys.reserve(ENTRY, PayabliEnvironment.SANDBOX, PayabliAttempt.Retry).key)
        }

    @Test
    fun `a charge that settled supersedes nothing`() =
        runBlocking {
            val keys = store()
            keys.reserve(ENTRY, PayabliEnvironment.SANDBOX, PayabliAttempt.NewPayment)
            val newer = keys.reserve(ENTRY, PayabliEnvironment.SANDBOX, PayabliAttempt.NewPayment)
            keys.settle(ENTRY, PayabliEnvironment.SANDBOX, newer.key)
            keys.supersede(ENTRY, PayabliEnvironment.SANDBOX, newer.key)

            assertEquals("key-1", keys.reserve(ENTRY, PayabliEnvironment.SANDBOX, PayabliAttempt.Retry).key)
        }

    /** The cap is on entry points, so one entry point's keys never refuse its own next sale. */
    @Test
    fun `the cap counts entry points, not keys`() =
        runBlocking {
            val keys = store()
            repeat(ChargeKeyStore.MAX) { keys.reserve(ENTRY, PayabliEnvironment.SANDBOX, PayabliAttempt.NewPayment) }

            val next = keys.reserve(ENTRY, PayabliEnvironment.SANDBOX, PayabliAttempt.NewPayment)

            assertEquals("key-${ChargeKeyStore.MAX + 1}", next.key)
        }

    @Test
    fun `a full store refuses rather than evicting a live attempt`() =
        runBlocking {
            val keys = store()
            repeat(ChargeKeyStore.MAX) { keys.reserve("entry-$it", PayabliEnvironment.SANDBOX, PayabliAttempt.Retry) }
            val refused =
                runCatching {
                    keys.reserve("entry-overflow", PayabliEnvironment.SANDBOX, PayabliAttempt.Retry)
                }.exceptionOrNull()
            assertTrue("$refused", refused is ChargeKeyStoreFullException)
            assertEquals(
                "an oldest unresolved attempt was evicted",
                "key-1",
                keys.reserve("entry-0", PayabliEnvironment.SANDBOX, PayabliAttempt.Retry).key,
            )
        }

    @Test
    fun `forgetting held keys empties the process map`() =
        runBlocking {
            val keys = store()
            keys.reserve(ENTRY, PayabliEnvironment.SANDBOX, PayabliAttempt.Retry)
            ChargeKeyStore.forgetHeld()
            assertFalse(keys.reserve(ENTRY, PayabliEnvironment.SANDBOX, PayabliAttempt.Retry).reused)
            assertEquals("key-2", keys.reserve(ENTRY, PayabliEnvironment.SANDBOX, PayabliAttempt.Retry).key)
        }
}
