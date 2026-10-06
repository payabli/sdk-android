package com.payabli.sdk.core.network.impl

import org.junit.Assert.assertEquals
import org.junit.Test

class ClientHeaderTest {
    private fun facts(
        sdkVersion: String = "0.1.0",
        osVersion: String = "15",
        hardware: String = "SM-S908U1",
        locale: String = "en-US",
        deviceId: String? = "0123456789abcdef0123456789abcdef",
    ) = ClientFacts(sdkVersion, osVersion, hardware, deviceId = { deviceId }, locale = { locale })

    @Test
    fun `every member, in the fixed order, as exact octets`() {
        assertEquals(
            "sdk-version=\"0.1.0\", platform=android, os-version=\"15\", hardware=\"SM-S908U1\", " +
                "locale=\"en-US\", device-id=\"0123456789abcdef0123456789abcdef\"",
            ClientHeader.valueOf(facts()),
        )
    }

    @Test
    fun `no device id means no device-id member`() {
        assertEquals(
            "sdk-version=\"0.1.0\", platform=android, os-version=\"15\", hardware=\"SM-S908U1\", locale=\"en-US\"",
            ClientHeader.valueOf(facts(deviceId = null)),
        )
    }

    @Test
    fun `a blank value leaves its member out and the rest keep their order`() {
        assertEquals(
            "sdk-version=\"0.1.0\", platform=android, hardware=\"SM-S908U1\", locale=\"en-US\"",
            ClientHeader.valueOf(facts(osVersion = " ", deviceId = "")),
        )
    }

    @Test
    fun `a value outside printable ASCII is left out, never transcoded`() {
        assertEquals(
            "sdk-version=\"0.1.0\", platform=android, os-version=\"15\", locale=\"en-US\"",
            ClientHeader.valueOf(facts(hardware = "Galaxy Ä", deviceId = null)),
        )
        assertEquals(
            "sdk-version=\"0.1.0\", platform=android, os-version=\"15\", locale=\"en-US\"",
            ClientHeader.valueOf(facts(hardware = "tab\there", deviceId = null)),
        )
    }

    @Test
    fun `a quote and a backslash are escaped inside the string`() {
        assertEquals(
            "sdk-version=\"0.1.0\", platform=android, os-version=\"15\", hardware=\"a\\\"b\\\\c\", locale=\"en-US\"",
            ClientHeader.valueOf(facts(hardware = "a\"b\\c", deviceId = null)),
        )
    }

    @Test
    fun `the locale and the device id are read on every call`() {
        var locale = "en-US"
        var deviceId: String? = null
        val facts = ClientFacts("0.1.0", "15", "SM-S908U1", deviceId = { deviceId }, locale = { locale })

        val first = ClientHeader.valueOf(facts)
        locale = "es-MX"
        deviceId = "0123456789abcdef0123456789abcdef"
        val second = ClientHeader.valueOf(facts)

        assertEquals(
            "sdk-version=\"0.1.0\", platform=android, os-version=\"15\", hardware=\"SM-S908U1\", locale=\"en-US\"",
            first,
        )
        assertEquals(
            "sdk-version=\"0.1.0\", platform=android, os-version=\"15\", hardware=\"SM-S908U1\", " +
                "locale=\"es-MX\", device-id=\"0123456789abcdef0123456789abcdef\"",
            second,
        )
    }
}
