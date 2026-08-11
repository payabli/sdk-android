package com.payabli.sdk.core.network.impl

import com.payabli.sdk.core.network.HttpMethod
import com.payabli.sdk.core.network.PayabliRequest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * The media type a body gets when its builder did not set one.
 *
 * The transport sends what the request carries, and `HttpURLConnection` writes a body with no declared type
 * as form encoding, so this is what stops a JSON body arriving labelled as a form.
 */
class JsonBodyDecorationTest {
    private val timeout = 5.seconds
    private val decoration = JsonBodyDecoration()

    private fun request(
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = """{"a":1}""".toByteArray(),
    ) = PayabliRequest(
        method = HttpMethod.POST,
        path = "/api/v2/MoneyIn/getpaid",
        headers = headers,
        body = body,
    )

    @Test
    fun `a body with no declared type is JSON`() =
        runTest(timeout = timeout) {
            val decorated = decoration.decorate(request())

            assertEquals("application/json", decorated.headers["Content-Type"])
        }

    @Test
    fun `a request with no body is left alone`() =
        runTest(timeout = timeout) {
            // A GET carries no body, and declaring a media type for one that does not exist would be a claim
            // about content that is not there.
            val decorated = decoration.decorate(request(body = null))

            assertNull(decorated.headers["Content-Type"])
        }

    @Test
    fun `a declared type is kept, whatever its case`() =
        runTest(timeout = timeout) {
            // The step defaults rather than overrides, so a body that names its own type keeps it. Header names
            // are case-insensitive, so a lower-case spelling counts as naming one.
            listOf("Content-Type", "content-type", "CONTENT-TYPE").forEach { name ->
                val declared = request(headers = mapOf(name to "application/x-www-form-urlencoded"))

                val decorated = decoration.decorate(declared)

                assertEquals(name, "application/x-www-form-urlencoded", decorated.headers[name])
                assertEquals(name, 1, decorated.headers.size)
            }
        }

    @Test
    fun `the request's other headers survive`() =
        runTest(timeout = timeout) {
            val decorated = decoration.decorate(request(headers = mapOf("idempotencyKey" to "key-1")))

            assertEquals("key-1", decorated.headers["idempotencyKey"])
            assertEquals("application/json", decorated.headers["Content-Type"])
        }

    @Test
    fun `the chain carries it`() =
        runTest(timeout = timeout) {
            // Registered rather than merely written: a decoration outside the factory's list runs for nobody.
            val chain = RequestDecorationFactory.chainFor(testAuth())

            val decorated = chain.applyTo(request())

            assertEquals("application/json", decorated.headers["Content-Type"])
        }
}
