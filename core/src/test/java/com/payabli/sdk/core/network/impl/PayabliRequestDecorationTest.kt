package com.payabli.sdk.core.network.impl

import com.payabli.sdk.core.network.HttpMethod
import com.payabli.sdk.core.network.PayabliRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The fold itself: pure, no HTTP, no transport. */
class PayabliRequestDecorationTest {
    private fun request(headers: Map<String, String> = emptyMap()) =
        PayabliRequest(HttpMethod.GET, "/api/ping", route = "/api/ping", headers = headers)

    private fun stamping(
        name: String,
        value: String,
    ) = PayabliRequestDecoration { it.withHeaders(mapOf(name to value)) }

    @Test
    fun `an empty chain is the identity`() =
        runTest {
            val original = request()
            assertSame(original, emptyList<PayabliRequestDecoration>().applyTo(original))
        }

    @Test
    fun `decorations run left to right`() =
        runTest {
            val order = mutableListOf<String>()

            fun recording(name: String) =
                PayabliRequestDecoration {
                    order += name
                    it
                }
            val chain = listOf(recording("first"), recording("second"))

            chain.applyTo(request())

            assertEquals(listOf("first", "second"), order)
        }

    @Test
    fun `a later decoration sees an earlier one's output`() =
        runTest {
            val chain =
                listOf(
                    stamping("X-First", "1"),
                    PayabliRequestDecoration { earlier ->
                        earlier.withHeaders(mapOf("X-Saw-First" to earlier.headers.getValue("X-First")))
                    },
                )

            val decorated = chain.applyTo(request())

            assertEquals("1", decorated.headers["X-Saw-First"])
        }

    @Test
    fun `a later decoration wins over an earlier one`() =
        runTest {
            val decorated = listOf(stamping("X-Who", "earlier"), stamping("X-Who", "later")).applyTo(request())
            assertEquals("later", decorated.headers["X-Who"])
        }

    @Test
    fun `a decoration wins over the caller's header`() =
        runTest {
            // If a caller's header could shadow a decoration's, an endpoint client could suppress an auth
            // header by supplying its own.
            val decorated = listOf(stamping("X-Who", "decoration")).applyTo(request(mapOf("X-Who" to "caller")))
            assertEquals("decoration", decorated.headers["X-Who"])
        }

    @Test
    fun `a differently-cased caller header is removed, not shadowed`() =
        runTest {
            // setRequestProperty replaces case-insensitively, so leaving both would let iteration order
            // decide which value reaches the wire.
            val decorated =
                listOf(stamping("Authorization", "decoration"))
                    .applyTo(request(mapOf("authorization" to "caller")))

            assertEquals(1, decorated.headers.size)
            assertEquals("decoration", decorated.headers.getValue("Authorization"))
            assertNull(decorated.headers["authorization"])
        }

    @Test
    fun `an unrelated caller header survives`() =
        runTest {
            val decorated =
                listOf(stamping("X-Added", "1")).applyTo(request(mapOf("X-Kept" to "yes")))

            assertEquals("yes", decorated.headers["X-Kept"])
            assertEquals("1", decorated.headers["X-Added"])
        }

    @Test
    fun `a decoration can contribute a body, not only headers`() =
        runTest {
            // The reason the seam is request-in-request-out: a future decoration stamps body fields.
            val decorated =
                listOf(PayabliRequestDecoration { it.withBody("""{"stamped":true}""".toByteArray()) })
                    .applyTo(request())

            assertEquals("""{"stamped":true}""", decorated.body?.toString(Charsets.UTF_8))
        }

    @Test
    fun `identity fields are carried through untouched`() =
        runTest {
            val original =
                PayabliRequest(
                    method = HttpMethod.POST,
                    path = "/api/v2/MoneyIn/capture/9",
                    route = "/api/v2/MoneyIn/capture/{id}",
                    query = listOf("a" to "1"),
                    isCredentialPinned = true,
                )

            val decorated = listOf(stamping("X-Added", "1")).applyTo(original)

            assertEquals(HttpMethod.POST, decorated.method)
            assertEquals("/api/v2/MoneyIn/capture/9", decorated.path)
            assertEquals("/api/v2/MoneyIn/capture/{id}", decorated.route)
            assertEquals(listOf("a" to "1"), decorated.query)
            // Nothing below `applyTo` reads the pin: the transport checks it on the request it was handed,
            // one layer above the chain. Asserted so `copyWith` stays complete, since a property dropped
            // there stays invisible until something below the chain starts reading it.
            assertTrue("the pin was dropped by the copy", decorated.isCredentialPinned)
        }

    @Test
    fun `a suspending decoration is cancellable`() =
        runTest {
            val entered = CompletableDeferred<Unit>()
            val chain =
                listOf(
                    PayabliRequestDecoration {
                        entered.complete(Unit)
                        CompletableDeferred<PayabliRequest>().await()
                    },
                )

            val job = launch { chain.applyTo(request()) }
            entered.await()
            job.cancelAndJoin()

            assertTrue(job.isCancelled)
        }
}
