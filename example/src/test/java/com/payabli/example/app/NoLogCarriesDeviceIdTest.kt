package com.payabli.example.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The device ID is shown and copied, never written to a log.
 *
 * Read per file rather than per line, because a log message is usually built a few lines before it is
 * recorded: a file that names the device ID calls no log sink at all.
 */
class NoLogCarriesDeviceIdTest {
    private val appSources: List<File> =
        File("src/main")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    @Test
    fun `no file that names the device ID writes to a log`() {
        assertEquals("no source was read, so this proves nothing", true, appSources.size > 50)

        val offenders =
            appSources
                .map { it to it.readText() }
                .filter { (_, text) -> DEVICE_ID in text && LOG_SINK.containsMatchIn(text) }
                .map { (file, _) -> file.invariantSeparatorsPath.substringAfter("/app/") }
                .sorted()

        assertEquals("these name the device ID and write to a log", emptyList<String>(), offenders)
    }

    @Test
    fun `the device ID is read somewhere`() {
        // The rule above holds for an app that never reads the device ID. This one fails if it stops.
        assertEquals(true, appSources.any { DEVICE_ID in it.readText() })
    }

    private companion object {
        const val DEVICE_ID = "deviceId"

        /** Platform logging, standard output, and the sample's own diagnostics log. */
        val LOG_SINK =
            Regex("""\bLog\.[a-z]+\(|\bprintln\(|\bprint\(|printStackTrace\(|System\.(out|err)|diagnostics\.record\(""")
    }
}
