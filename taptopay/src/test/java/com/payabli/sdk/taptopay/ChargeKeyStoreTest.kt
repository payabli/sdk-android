package com.payabli.sdk.taptopay

import com.payabli.sdk.core.config.PayabliEnvironment
import com.payabli.sdk.core.model.PayabliAttempt
import com.payabli.sdk.core.model.PayabliErrorType
import com.payabli.sdk.core.model.PayabliException
import com.payabli.sdk.core.network.IDEMPOTENCY_KEY_MAX_AGE
import com.payabli.sdk.taptopay.model.TapToPayInvoiceData
import com.payabli.sdk.taptopay.model.TapToPayPaymentDetails
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
import java.math.BigDecimal
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

    /** A sale described by its amount, fee, currency and invoice. */
    private fun sale(
        amount: String = "10.00",
        fee: String = "0",
        currency: String? = "USD",
        invoice: String? = null,
    ) = ChargeDescription.of(
        TapToPayPaymentDetails(BigDecimal(amount), BigDecimal(fee), currency),
        TapToPayInvoiceData(invoice),
    )

    /** Reserves a declared retry of [description], or returns the type it was refused with. */
    private suspend fun ChargeKeyStore.retry(description: ChargeDescription): Any =
        try {
            reserve(ENTRY, PayabliEnvironment.SANDBOX, PayabliAttempt.Retry, description)
                .also {
                    supersede(ENTRY, PayabliEnvironment.SANDBOX, it.key)
                }.key
        } catch (refused: PayabliException) {
            refused.type
        }

    /** A held key goes out only with the sale it was sent for, so a retry of another sale is refused. */
    @Test
    fun `a declared retry of a different sale is refused and the held key stays`() =
        runBlocking {
            val cases =
                listOf(
                    Triple("amount", sale(), sale(amount = "10.01")),
                    Triple("service fee", sale(), sale(fee = "0.50")),
                    Triple("currency", sale(), sale(currency = "CAD")),
                    Triple("invoice", sale(invoice = "inv-1"), sale(invoice = "inv-2")),
                )
            for ((field, first, retry) in cases) {
                ChargeKeyStore.forgetHeld()
                minted.set(0)
                val keys = store()
                val held = keys.reserve(ENTRY, PayabliEnvironment.SANDBOX, PayabliAttempt.NewPayment, first)
                keys.supersede(ENTRY, PayabliEnvironment.SANDBOX, held.key)

                assertEquals(field, PayabliErrorType.RETRY_NOT_SAME_PAYMENT, keys.retry(retry))
                assertEquals("$field: the held key was lost", "key-1", keys.retry(first))
            }
        }

    /** Compared as the service charges it, so a retry of the same sale is never refused for its spelling. */
    @Test
    fun `a declared retry the service would charge the same way claims the held key`() =
        runBlocking {
            val cases =
                listOf(
                    "rounding" to sale(amount = "10.001"),
                    "currency spelling" to sale(currency = " usd "),
                    "invoice set only on the retry" to sale(invoice = "inv-9"),
                )
            for ((field, retry) in cases) {
                ChargeKeyStore.forgetHeld()
                minted.set(0)
                val keys = store()
                val held = keys.reserve(ENTRY, PayabliEnvironment.SANDBOX, PayabliAttempt.NewPayment, sale())
                keys.supersede(ENTRY, PayabliEnvironment.SANDBOX, held.key)

                assertEquals(field, "key-1", keys.retry(retry))
            }
        }

    /** A resend keeps the sale the key was minted for, so a later retry cannot rewrite what it is compared with. */
    @Test
    fun `a key held again keeps the sale it was first sent for`() =
        runBlocking {
            val keys = store()
            val held = keys.reserve(ENTRY, PayabliEnvironment.SANDBOX, PayabliAttempt.NewPayment, sale())
            keys.supersede(ENTRY, PayabliEnvironment.SANDBOX, held.key)
            assertEquals("key-1", keys.retry(sale(invoice = "inv-1")))

            assertEquals("key-1", keys.retry(sale(invoice = "inv-2")))
        }

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
            val first = keys.reserve(ENTRY, PayabliEnvironment.SANDBOX, PayabliAttempt.Retry)
            assertEquals("key-1", first.key)
            keys.supersede(ENTRY, PayabliEnvironment.SANDBOX, first.key, arrivedAt = first.reservedAt)
            clock.addAndGet(IDEMPOTENCY_KEY_MAX_AGE.inWholeNanoseconds)
            assertEquals("key-2", keys.reserve(ENTRY, PayabliEnvironment.SANDBOX, PayabliAttempt.Retry).key)
            assertFalse(keys.reserve(ENTRY, PayabliEnvironment.SANDBOX, PayabliAttempt.Retry).key == "key-1")
        }

    /** The clock restarts at the resend that may have reached the service, never earlier and never without one. */
    @Test
    fun `a resend that may have arrived restarts its key's clock, and a supersede without one does not`() =
        runBlocking {
            val keys = store()
            val half = IDEMPOTENCY_KEY_MAX_AGE.inWholeNanoseconds / 2
            val first = keys.reserve(ENTRY, PayabliEnvironment.SANDBOX, PayabliAttempt.NewPayment)
            clock.addAndGet(half)
            val resend = keys.reserve(ENTRY, PayabliEnvironment.SANDBOX, PayabliAttempt.Retry)
            keys.supersede(ENTRY, PayabliEnvironment.SANDBOX, resend.key, arrivedAt = resend.reservedAt)
            keys.supersede(ENTRY, PayabliEnvironment.SANDBOX, resend.key, arrivedAt = first.reservedAt)
            clock.addAndGet(half)
            assertEquals("key-1", keys.reserve(ENTRY, PayabliEnvironment.SANDBOX, PayabliAttempt.Retry).key)

            keys.supersede(ENTRY, PayabliEnvironment.SANDBOX, "key-1")
            clock.addAndGet(half)
            assertEquals("key-2", keys.reserve(ENTRY, PayabliEnvironment.SANDBOX, PayabliAttempt.Retry).key)
        }

    /** Another entry point's sweep runs while a resend is on the wire, and the key must outlive that send. */
    @Test
    fun `a key being resent is not aged out by another entry point's sweep`() =
        runBlocking {
            val keys = store()
            val first = keys.reserve(ENTRY, PayabliEnvironment.SANDBOX, PayabliAttempt.NewPayment)
            keys.supersede(ENTRY, PayabliEnvironment.SANDBOX, first.key, arrivedAt = first.reservedAt)
            clock.addAndGet(IDEMPOTENCY_KEY_MAX_AGE.inWholeNanoseconds - 1)
            val resend = keys.reserve(ENTRY, PayabliEnvironment.SANDBOX, PayabliAttempt.Retry)
            clock.addAndGet(2)
            keys.reserve(OTHER_ENTRY, PayabliEnvironment.SANDBOX, PayabliAttempt.NewPayment)
            keys.supersede(ENTRY, PayabliEnvironment.SANDBOX, resend.key, arrivedAt = resend.reservedAt)

            assertEquals("key-1", keys.reserve(ENTRY, PayabliEnvironment.SANDBOX, PayabliAttempt.Retry).key)
        }

    /** Held only for the send: a resend answered 409 leaves the clock where it was, so the key then ages out. */
    @Test
    fun `a resend that ends without arriving ages out once it is over`() =
        runBlocking {
            val keys = store()
            val first = keys.reserve(ENTRY, PayabliEnvironment.SANDBOX, PayabliAttempt.NewPayment)
            keys.supersede(ENTRY, PayabliEnvironment.SANDBOX, first.key, arrivedAt = first.reservedAt)
            clock.addAndGet(IDEMPOTENCY_KEY_MAX_AGE.inWholeNanoseconds - 1)
            val resend = keys.reserve(ENTRY, PayabliEnvironment.SANDBOX, PayabliAttempt.Retry)
            clock.addAndGet(2)
            keys.reserve(OTHER_ENTRY, PayabliEnvironment.SANDBOX, PayabliAttempt.NewPayment)
            keys.supersede(ENTRY, PayabliEnvironment.SANDBOX, resend.key)

            assertEquals("key-3", keys.reserve(ENTRY, PayabliEnvironment.SANDBOX, PayabliAttempt.Retry).key)
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
