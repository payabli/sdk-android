package com.payabli.sdk.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * What survives a path segment and what does not.
 *
 * The character-level questions live here, where the encoder does. A route's own tests assert the path it
 * builds; they do not re-derive the escaping, or every module that builds a path owns a copy of this table
 * as well as a copy of the code.
 */
class PercentEncodingTest {
    @Test
    fun `the unreserved set is left readable`() {
        // A real identifier is not turned into escape sequences. RFC 3986 Section 2.3 is the whole set:
        // letters, digits, and these four.
        val unreserved = "abcXYZ019-._~"

        assertEquals(unreserved, PercentEncoding.segment(unreserved))
    }

    @Test
    fun `the characters that would change the request are encoded`() {
        // Without encoding, each of these makes the request mean something else: a query, a fragment,
        // another route, a parameter, or a new authority.
        assertEquals("101-abc%3Fx%3D1", PercentEncoding.segment("101-abc?x=1"))
        assertEquals("101%2Fvoid", PercentEncoding.segment("101/void"))
        assertEquals("101%23top", PercentEncoding.segment("101#top"))
        assertEquals("101%3Bv", PercentEncoding.segment("101;v"))
        assertEquals("%2F%2Fevil.example", PercentEncoding.segment("//evil.example"))
    }

    @Test
    fun `a dot segment cannot climb out of the path`() {
        // `..` is unreserved character by character, so it survives as text — which is correct here and is
        // why it is not this function's job to refuse it. What matters is that the separator does not.
        assertEquals("..%2F..%2Fetc", PercentEncoding.segment("../../etc"))
    }

    @Test
    fun `a space is not a plus`() {
        // URLEncoder would write `+`, which is form encoding and wrong in a path. This is the difference
        // that makes the standard library unusable here.
        assertEquals("a%20b", PercentEncoding.segment("a b"))
    }

    @Test
    fun `non-ascii is encoded from its utf-8 bytes`() {
        assertEquals("%C3%A9", PercentEncoding.segment("é"))
        // Four bytes, outside the basic multilingual plane, so a surrogate pair on the Kotlin side.
        assertEquals("%F0%9F%92%B3", PercentEncoding.segment("💳"))
    }

    @Test
    fun `the hexadecimal digits are upper case`() {
        // RFC 3986 Section 6.2.2.1: a producer writes them upper case. Lower case would be accepted by most
        // servers and would still make two SDKs disagree on the same identifier.
        assertEquals("%7B%7D", PercentEncoding.segment("{}"))
    }

    @Test
    fun `every ascii character is either kept or encoded, and the kept set is exactly the unreserved one`() {
        // Exhaustive over the range where the decision is made, and derived from the RFC rather than from
        // the implementation, so it holds whatever technique the encoder uses to classify a byte.
        val unreserved = (('A'..'Z') + ('a'..'z') + ('0'..'9')).toSet() + setOf('-', '.', '_', '~')

        for (code in 0..127) {
            val char = code.toChar()
            val expected = if (char in unreserved) char.toString() else "%%%02X".format(code)

            assertEquals("code point $code", expected, PercentEncoding.segment(char.toString()))
        }
    }

    @Test
    fun `a character above ascii is encoded from every one of its utf-8 bytes`() {
        // The reference reads each byte unsigned. Every byte over 0x7F is negative in Kotlin, so an encoder
        // mishandling that sign fails here rather than somewhere a reader would think to look. Written as
        // escapes: the first code point needing two, three and four bytes, and the last of each width.
        listOf("\u00e9", "\u0080", "\u07ff", "\u0800", "\uffff", "\ud83d\udcb3").forEach { value ->
            val expected =
                value.toByteArray(Charsets.UTF_8).joinToString("") { "%%%02X".format(it.toInt() and 0xFF) }

            assertEquals(value, expected, PercentEncoding.segment(value))
        }
    }

    @Test
    fun `an empty value encodes to an empty segment rather than being refused`() {
        // Stated because it is a trap: the caller gets a path with a trailing slash and no identifier, which
        // is a different route. Whether that is allowed is the caller's question, and this says so.
        assertEquals("", PercentEncoding.segment(""))
    }

    @Test
    fun `a path is built from its own template, wherever the placeholder sits`() {
        // The template is the only form a log may carry and the path is what goes on the wire, so both come
        // from one literal. A placeholder that is not last is covered because nothing says it has to be.
        assertEquals(
            "/api/v2/MoneyIn/capture/12-abc",
            PercentEncoding.pathFrom("/api/v2/MoneyIn/capture/{transId}", "12-abc"),
        )
        assertEquals("/a/one/b", PercentEncoding.pathFrom("/a/{id}/b", "one"))
    }

    @Test
    fun `the segment is placed as given, not encoded a second time`() {
        // Callers do not agree on what makes a segment safe: a transaction identifier is encoded, an entry
        // point is refused unless it already is. Encoding here would double-encode the first.
        assertEquals("/a/%2F/b", PercentEncoding.pathFrom("/a/{id}/b", PercentEncoding.segment("/")))
    }

    @Test
    fun `a template with no placeholder is refused rather than returned unchanged`() {
        // A route wired to this helper without one is a defect here, not a value a caller could correct.
        // Returning the template would send every request to the same literal path.
        listOf("/api/v2/MoneyIn/getpaid", "/a/{unclosed", "/a/}{").forEach { template ->
            assertThrows(IllegalArgumentException::class.java) { PercentEncoding.pathFrom(template, "one") }
        }
    }
}
