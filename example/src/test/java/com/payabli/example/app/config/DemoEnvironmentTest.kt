package com.payabli.example.app.config

import com.payabli.example.app.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoEnvironmentTest {
    // --- named ---

    @Test
    fun `each label names its own environment`() {
        // Driven off entries, so an environment added without a test still
        // has its label checked.
        DemoEnvironment.entries.forEach { environment ->
            assertEquals(environment, DemoEnvironment.named(environment.label))
        }
    }

    @Test
    fun `case and surrounding whitespace are ignored`() {
        assertEquals(DemoEnvironment.QA, DemoEnvironment.named("QA"))
        assertEquals(DemoEnvironment.QA, DemoEnvironment.named("  qa  "))
        assertEquals(DemoEnvironment.SANDBOX, DemoEnvironment.named("SandBox"))
    }

    @Test
    fun `an unrecognised label names nothing`() {
        assertNull(DemoEnvironment.named("qua"))
        assertNull(DemoEnvironment.named("staging"))
    }

    @Test
    fun `a blank label names nothing`() {
        // Blank never reaches here: the build treats it as absent and substitutes the default, which
        // is what keeps the template's `payabli.demo.environment=` line working. So blank arriving
        // here means something else went wrong, and answering an environment would hide it.
        assertNull(DemoEnvironment.named(""))
        assertNull(DemoEnvironment.named("   "))
    }

    @Test
    fun `an environment is not named by its enum constant`() {
        // The setting is the label, and the enum constant is not a second spelling of it.
        assertNull(DemoEnvironment.named("PRODUCTION_ENV"))
        assertEquals(DemoEnvironment.PRODUCTION, DemoEnvironment.named("production"))
    }

    // --- the hosts each environment resolves to ---

    @Test
    fun `qa points at the qa api`() {
        assertEquals("https://api-qa.payabli.com", DemoEnvironment.QA.baseUrl)
        assertEquals("api-qa.payabli.com", DemoEnvironment.QA.host)
    }

    @Test
    fun `no two environments share a host`() {
        val hosts = DemoEnvironment.entries.map { it.host }
        assertEquals(hosts.size, hosts.toSet().size)
    }

    @Test
    fun `every environment is reached over https`() {
        DemoEnvironment.entries.forEach { environment ->
            assertTrue(environment.baseUrl, environment.baseUrl.startsWith("https://"))
        }
    }

    // --- the message an unrecognised setting produces ---

    @Test
    fun `the label list covers every environment`() {
        // This string is the whole of what a reader is told the accepted values are, so an
        // environment missing from it is an environment nobody can find.
        DemoEnvironment.entries.forEach { environment ->
            assertTrue(DemoEnvironment.labels, DemoEnvironment.labels.contains(environment.label))
        }
    }

    // --- what a configuration derives from the setting ---

    /** Only the environment setting varies; the rest is filler these tests never read. */
    private fun configuredWith(setting: String) =
        DemoConfiguration(
            entryPoint = "entry0000",
            appId = "com.payabli.example.app",
            signingCertificate = "AB:CD",
            environmentSetting = setting,
            diagnosticsEnabled = true,
        )

    @Test
    fun `the setting decides the environment`() {
        assertEquals(DemoEnvironment.QA, configuredWith("qa").environment)
        assertEquals(DemoEnvironment.PRODUCTION, configuredWith("production").environment)
    }

    @Test
    fun `a recognised setting is not a problem`() {
        assertNull(configuredWith("qa").environmentProblem)
    }

    @Test
    fun `an unrecognised setting falls back and says so`() {
        val configuration = configuredWith("qua")
        assertEquals(DemoEnvironment.DEFAULT, configuration.environment)

        val problem = configuration.environmentProblem
        assertNotNull("a substituted environment was not explained", problem)
        // The value that was configured, so a reader is told what to go and fix.
        assertTrue(problem!!, problem.contains("qua"))
        assertTrue("the property is not named", problem.contains("payabli.demo.environment"))
        assertTrue("the accepted values are not listed", problem.contains(DemoEnvironment.QA.label))
    }

    @Test
    fun `an empty setting still has an environment`() {
        // The build substitutes the default for a blank, so this is the belt on that: a configuration
        // handed one still has an environment to talk to.
        assertEquals(DemoEnvironment.DEFAULT, configuredWith("").environment)
        assertNotNull(configuredWith("").environmentProblem)
    }

    @Test
    fun `the two constructors agree`() {
        // Previews and tests hand over an environment, the build hands over its label. They have to
        // produce the same configuration, or one of the two is testing a state the app cannot reach.
        DemoEnvironment.entries.forEach { environment ->
            val fromEnvironment =
                DemoConfiguration("entry0000", "com.payabli.example.app", "AB:CD", environment, true)
            assertEquals(configuredWith(environment.label), fromEnvironment)
            assertEquals(environment, fromEnvironment.environment)
            assertNull(fromEnvironment.environmentProblem)
        }
    }

    // --- what ships when nothing is configured ---

    @Test
    fun `the fallback is sandbox`() {
        assertEquals(DemoEnvironment.SANDBOX, DemoEnvironment.DEFAULT)
    }

    @Test
    fun `the shipped default setting names an environment`() {
        // Pins the default in the build file against the labels here. They are two files, and a typo
        // in the build file otherwise reaches a device as a silent fall back to sandbox with a
        // warning on the Setup screen.
        assertNotNull(
            "payabli.demo.environment default names no environment",
            DemoEnvironment.named(BuildConfig.DEMO_ENVIRONMENT),
        )
        assertNull(DemoConfiguration.fromBuildConfig().environmentProblem)
    }
}
