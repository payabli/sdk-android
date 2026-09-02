package com.payabli.example.app.sdk

import com.payabli.example.app.BuildConfig
import com.payabli.sdk.core.config.PayabliEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoEnvironmentTest {
    // --- named ---

    @Test
    fun `each name names its own environment`() {
        // Driven off the offered list, so an environment a build adds still has its name checked.
        DemoEnvironment.offered.forEach { environment ->
            assertEquals(environment, DemoEnvironment.named(environment.label))
        }
    }

    @Test
    fun `case and surrounding whitespace are ignored`() {
        assertEquals(DemoEnvironment.SANDBOX, DemoEnvironment.named("SANDBOX"))
        assertEquals(DemoEnvironment.SANDBOX, DemoEnvironment.named("  sandbox  "))
        assertEquals(DemoEnvironment.SANDBOX, DemoEnvironment.named("SandBox"))
    }

    @Test
    fun `an unrecognised name names nothing`() {
        // Values no build offers, rather than one a build might. A bench adds an environment through
        // `payabli.demo.extraEnvironments`, so asserting a plausible name is absent would go red on exactly
        // the machines the setting exists for.
        assertNull(DemoEnvironment.named("qua"))
        assertNull(DemoEnvironment.named("staging"))
    }

    @Test
    fun `a blank name names nothing`() {
        // Blank never reaches here: the build treats it as absent and substitutes the default, which
        // is what keeps the template's `payabli.demo.environment=` line working. So blank arriving
        // here means something else went wrong, and answering an environment would hide it.
        assertNull(DemoEnvironment.named(""))
        assertNull(DemoEnvironment.named("   "))
    }

    @Test
    fun `an environment is not named by its Kotlin constant`() {
        // The setting is the name the SDK carries, and the constant is not a second spelling of it.
        assertNull(DemoEnvironment.named("PRODUCTION_ENV"))
        assertEquals(DemoEnvironment.PRODUCTION, DemoEnvironment.named("production"))
    }

    // --- what the offered list may contain ---

    @Test
    fun `the two are always offered, first, whatever the setting says`() {
        // The setting appends. A value that could remove one of the two, or push one behind an addition,
        // would be repointing what the sample talks to rather than adding to it.
        assertEquals(
            listOf(DemoEnvironment.SANDBOX, DemoEnvironment.PRODUCTION),
            DemoEnvironment.offered.take(2),
        )
    }

    @Test
    fun `the additions are what the setting named`() {
        // Holds in a build that added an environment and in one that did not, and goes red if `offered`
        // stops reading the setting, which is the whole mechanism. A build carrying only the committed two
        // cannot tell a working setting from an ignored one, so this is written against the setting.
        //
        // The expected list is derived from the SDK and from the two constants, never from `offered` or from
        // `named`. Filtering by `DemoEnvironment.named` was the first version of this, and it could not fail:
        // an `offered` that ignored the setting emptied both sides at once and the comparison held.
        val expected =
            BuildConfig.DEMO_EXTRA_ENVIRONMENTS
                .split(",")
                .map(String::trim)
                .filter(String::isNotEmpty)
                .mapNotNull(PayabliEnvironment::named)
                .map { it.name }
                .filterNot { it == DemoEnvironment.SANDBOX.label || it == DemoEnvironment.PRODUCTION.label }
                .distinct()

        assertEquals(expected, DemoEnvironment.offered.drop(2).map { it.label })
    }

    @Test
    fun `the app offers nothing the SDK does not carry`() {
        // What stops the setting inventing an environment: a name with no SDK environment behind it has no
        // origin to resolve to, and a picker entry that reaches nothing is worse than one that is absent.
        DemoEnvironment.offered.forEach { environment ->
            assertTrue("$environment", PayabliEnvironment.named(environment.label) != null)
        }
    }

    @Test
    fun `no two environments share a host`() {
        val hosts = DemoEnvironment.offered.map { it.host }
        assertEquals(hosts.size, hosts.toSet().size)
    }

    @Test
    fun `every environment is reached over https`() {
        DemoEnvironment.offered.forEach { environment ->
            assertTrue(environment.baseUrl, environment.baseUrl.startsWith("https://"))
        }
    }

    @Test
    fun `the name list covers every offered environment`() {
        // This string is the whole of what a reader is told the accepted values are, so an
        // environment missing from it is an environment nobody can find.
        DemoEnvironment.offered.forEach { environment ->
            assertTrue(DemoEnvironment.labels, DemoEnvironment.labels.contains(environment.label))
        }
    }

    @Test
    fun `the fallback is sandbox`() {
        assertEquals(DemoEnvironment.SANDBOX, DemoEnvironment.DEFAULT)
    }
}
