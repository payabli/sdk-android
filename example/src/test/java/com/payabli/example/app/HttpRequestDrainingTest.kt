package com.payabli.example.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

/**
 * What the fake token servers do before they answer.
 *
 * Answering a request that was only partly read races the client's write, and the app reports that as a reset
 * rather than as the reply it was sent. These assert the stream is positioned after the request, or that the
 * drain refused it, which is what makes the caller close instead of answering.
 */
class HttpRequestDrainingTest {
    @Test
    fun `a request with no body leaves the stream at its end`() {
        val stream = streamOf("GET /health HTTP/1.1\r\nHost: localhost\r\n\r\n")

        drainHttpRequest(stream)

        assertEquals("nothing should be left to read", -1, stream.read())
    }

    @Test
    fun `a declared body is read to its end`() {
        val stream = streamOf("POST / HTTP/1.1\r\nContent-Length: 9\r\n\r\n{\"a\":\"b\"}TRAILING")

        drainHttpRequest(stream)

        assertEquals("the body should be consumed and nothing more", "TRAILING", stream.readBytes().decodeToString())
    }

    @Test
    fun `a body shorter than it declared is refused`() {
        val stream = streamOf("POST / HTTP/1.1\r\nContent-Length: 40\r\n\r\nshort")

        val failure = assertThrows(IOException::class.java) { drainHttpRequest(stream) }

        assertEquals("request body ended 35 bytes early", failure.message)
    }

    @Test
    fun `a body larger than the cap is refused before it is read`() {
        val stream = streamOf("POST / HTTP/1.1\r\nContent-Length: 999999\r\n\r\n")

        val failure = assertThrows(IOException::class.java) { drainHttpRequest(stream) }

        assertEquals("request body exceeded 65536 bytes", failure.message)
    }

    @Test
    fun `a negative length is refused rather than read as an empty body`() {
        val stream = streamOf("POST / HTTP/1.1\r\nContent-Length: -1\r\n\r\nbody")

        val failure = assertThrows(IOException::class.java) { drainHttpRequest(stream) }

        assertEquals("Content-Length is negative: -1", failure.message)
    }

    @Test
    fun `a negative chunk size is refused`() {
        val stream = streamOf("POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n-1\r\nab\r\n")

        val failure = assertThrows(IOException::class.java) { drainHttpRequest(stream) }

        assertEquals("chunk size is negative: -1", failure.message)
    }

    @Test
    fun `a length that is not a number is refused rather than read as none`() {
        val stream = streamOf("POST / HTTP/1.1\r\nContent-Length: banana\r\n\r\nbody")

        val failure = assertThrows(IOException::class.java) { drainHttpRequest(stream) }

        assertEquals("Content-Length is not a number: banana", failure.message)
    }

    @Test
    fun `a chunked body is read by its framing`() {
        val stream =
            streamOf(
                "POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n" +
                    "4\r\nabcd\r\n" +
                    "2\r\nef\r\n" +
                    "0\r\n\r\n" +
                    "TRAILING",
            )

        drainHttpRequest(stream)

        assertEquals("TRAILING", stream.readBytes().decodeToString())
    }

    @Test
    fun `a chunk size carrying an extension is read`() {
        val stream = streamOf("POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n3;name=v\r\nabc\r\n0\r\n\r\nX")

        drainHttpRequest(stream)

        assertEquals("X", stream.readBytes().decodeToString())
    }

    @Test
    fun `a chunked body with trailers is read past them`() {
        val stream =
            streamOf("POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n1\r\na\r\n0\r\nExpires: soon\r\n\r\nX")

        drainHttpRequest(stream)

        assertEquals("X", stream.readBytes().decodeToString())
    }

    @Test
    fun `a chunk size that is not hexadecimal is refused`() {
        val stream = streamOf("POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\nzz\r\nab\r\n")

        val failure = assertThrows(IOException::class.java) { drainHttpRequest(stream) }

        assertEquals("chunk size is not hexadecimal: zz", failure.message)
    }

    /** `identity` declares no chunked body, so the request has none and nothing is drained. */
    @Test
    fun `a transfer encoding that is not chunked declares no body`() {
        val stream = streamOf("POST / HTTP/1.1\r\nTransfer-Encoding: identity\r\n\r\nX")

        drainHttpRequest(stream)

        assertEquals("X", stream.readBytes().decodeToString())
    }

    @Test
    fun `headers past the cap are refused`() {
        val stream = streamOf("GET / HTTP/1.1\r\nX-Long: " + "a".repeat(20_000) + "\r\n\r\n")

        val failure = assertThrows(IOException::class.java) { drainHttpRequest(stream) }

        assertEquals("request headers exceeded 16384 bytes", failure.message)
    }

    private fun streamOf(request: String) = ByteArrayInputStream(request.toByteArray())
}
