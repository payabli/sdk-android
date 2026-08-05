package com.payabli.sdk.taptopay.attestation.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val ASSERTION = "YSBERVIgc2lnbmF0dXJl"
private const val KEY_ID = "a-keystore-alias"
private const val DEVICE_ID = "a-device-id"
private const val TIMESTAMP = "2026-08-04T12:00:00.000+0000"

private fun assertionWith(
    assertion: String = ASSERTION,
    keyId: String = KEY_ID,
    deviceId: String = DEVICE_ID,
    timestamp: String = TIMESTAMP,
) = DeviceAssertion(assertion, keyId, deviceId, timestamp)

class DeviceAssertionTest {
    @Test
    fun `the four headers carry the four values under the names the server reads`() {
        assertEquals(
            mapOf(
                "X-App-Assertion" to ASSERTION,
                "X-App-KeyId" to KEY_ID,
                "X-Device-Id" to DEVICE_ID,
                "X-Assertion-Timestamp" to TIMESTAMP,
            ),
            assertionWith().asHeaders(),
        )
    }

    @Test
    fun `a blank value in any position is refused`() {
        // Each position separately: a single case would pass with three of the four checks deleted.
        assertTrue(runCatching { assertionWith(assertion = "") }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { assertionWith(keyId = " ") }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { assertionWith(deviceId = "") }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { assertionWith(timestamp = "\t") }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `a value that cannot sit in a header is refused in every position`() {
        // A line feed here would be header injection, and HttpURLConnection would additionally throw an
        // unchecked IllegalArgumentException that escapes before the transport can map it — so the caller
        // would see the wrong exception type for the wrong reason. Refused where the value enters instead.
        assertTrue(
            runCatching { assertionWith(assertion = "sig\r\nX-Evil: 1") }.exceptionOrNull() is IllegalArgumentException,
        )
        assertTrue(runCatching { assertionWith(keyId = "alias\n") }.exceptionOrNull() is IllegalArgumentException)
        // NUL sits below the printable floor and NEL above its ceiling. The check is a range rather
        // than a hunt for the two characters that make injection work, so a merely unrepresentable
        // value is refused too.
        assertTrue(runCatching { assertionWith(deviceId = "id\u0000") }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(
            runCatching { assertionWith(timestamp = "$TIMESTAMP\u0085") }.exceptionOrNull() is IllegalArgumentException,
        )
    }

    @Test
    fun `edge whitespace is refused in every position`() {
        // A space is the printable floor, so the range check accepts one at either end while HTTP treats it as
        // padding the server may strip. On the timestamp that is fatal and silent: the signer hashed the
        // untrimmed string, the server hashes what arrived, and the assertion fails verification with nothing
        // naming whitespace as the cause.
        assertTrue(
            runCatching { assertionWith(assertion = " $ASSERTION") }.exceptionOrNull() is IllegalArgumentException,
        )
        assertTrue(runCatching { assertionWith(keyId = "$KEY_ID ") }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(
            runCatching { assertionWith(deviceId = " $DEVICE_ID ") }.exceptionOrNull() is IllegalArgumentException,
        )
        assertTrue(
            runCatching { assertionWith(timestamp = "$TIMESTAMP ") }.exceptionOrNull() is IllegalArgumentException,
        )
    }

    @Test
    fun `the timestamp's shape is not validated, and an interior space survives`() {
        // Two facts, one value, because the value demonstrates both. The server re-derives what was signed from
        // this string alone, so it has to be the signer's bytes verbatim: validating a format here would be this
        // SDK asserting a shape it does not itself produce, and the first signer spelling the offset differently
        // would be refused by its own client. The interior space also shows the edge-whitespace rule above was
        // not over-corrected into rejecting all whitespace — HTTP pads only the edges, so this survives the
        // round trip byte for byte and a signer is free to have signed it.
        assertEquals("17 Aug 2026", assertionWith(timestamp = "17 Aug 2026").asHeaders()["X-Assertion-Timestamp"])
    }

    @Test
    fun `the rejection names the field and never the value`() {
        val message =
            runCatching { assertionWith(assertion = "sig\r\ninjected") }
                .exceptionOrNull()
                ?.message
                .orEmpty()

        assertTrue(message.contains("assertion"))
        // Three of the four are key material, device identity or signed input, and an exception message
        // reaches diagnostics the logger cannot redact.
        assertFalse(message.contains("injected"))
    }

    @Test
    fun `the identity prints none of its three either`() {
        val rendered =
            DeviceIdentity(
                deviceId = "a-device-id",
                keyId = "a-keystore-alias",
                publicKey = "a-public-key",
            ).toString()

        // All three are device identity or key material, and this type is passed around the attestation flow,
        // so it reaches a diagnostic more readily than most.
        assertEquals("DeviceIdentity()", rendered)
        assertFalse(rendered.contains("a-device-id"))
        assertFalse(rendered.contains("a-keystore-alias"))
        assertFalse(rendered.contains("a-public-key"))
    }

    @Test
    fun `toString carries none of the four`() {
        val rendered = assertionWith().toString()

        assertEquals("DeviceAssertion()", rendered)
        assertFalse(rendered.contains(ASSERTION))
        assertFalse(rendered.contains(KEY_ID))
        assertFalse(rendered.contains(DEVICE_ID))
        assertFalse(rendered.contains(TIMESTAMP))
    }
}
