package com.payabli.example.app.demo.config

import com.payabli.example.app.BuildConfig
import com.payabli.example.app.sdk.DemoEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a configuration derives from `payabli.demo.environment`. The list itself is `DemoEnvironmentTest`. */
class DemoConfigurationEnvironmentTest {
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
        assertEquals(DemoEnvironment.SANDBOX, configuredWith("sandbox").environment)
        assertEquals(DemoEnvironment.PRODUCTION, configuredWith("production").environment)
    }

    @Test
    fun `a recognised setting is not a problem`() {
        assertNull(configuredWith("sandbox").environmentProblem)
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
        assertTrue("the accepted values are not listed", problem.contains(DemoEnvironment.SANDBOX.label))
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
        // Previews and tests hand over an environment, the build hands over its name. They have to
        // produce the same configuration, or one of the two is testing a state the app cannot reach.
        DemoEnvironment.offered.forEach { environment ->
            val fromEnvironment =
                DemoConfiguration("entry0000", "com.payabli.example.app", "AB:CD", environment, true)
            assertEquals(configuredWith(environment.label), fromEnvironment)
            assertEquals(environment, fromEnvironment.environment)
            assertNull(fromEnvironment.environmentProblem)
        }
    }

    @Test
    fun `the build file's default is the fallback environment`() {
        // The literal and DEMO_ENVIRONMENT's fallback are in two files, and this is the only thing
        // holding them equal. Let them drift and a build resolves one environment while the code
        // names another, which reaches a device saying it is pointed somewhere it is not.
        val configured = BuildFileDefaults.of("payabli.demo.environment")
        assertNotNull("no payabli.demo.environment default in ${BuildFileDefaults.location}", configured)
        assertEquals(DemoEnvironment.DEFAULT.label, configured)
    }

    @Test
    fun `the build file adds no environment of its own`() {
        // What a checkout of this repository builds. A machine that wants another adds it in its own Gradle
        // properties, and this literal stays empty.
        val configured = BuildFileDefaults.of("payabli.demo.extraEnvironments")
        assertNotNull("no payabli.demo.extraEnvironments default in ${BuildFileDefaults.location}", configured)
        assertEquals("", configured)
    }

    @Test
    fun `the setting this build resolved names an environment`() {
        // Fails on a typo in a developer's own secrets.properties, which otherwise reaches a device
        // as a silent fall back with a warning on the Setup screen.
        assertNotNull(
            "payabli.demo.environment=${BuildConfig.DEMO_ENVIRONMENT} names no environment",
            DemoEnvironment.named(BuildConfig.DEMO_ENVIRONMENT),
        )
        assertNull(DemoConfiguration.fromBuildConfig().environmentProblem)
    }
}
