package com.payabli.sdk.telemetry

import com.payabli.sdk.core.telemetry.TelemetryBootstrap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The contract that makes linking this artifact the whole integration.
 *
 * The session finds this module by name, and every part of that lookup can be broken by an edit that looks
 * harmless: renaming the class, moving its package, giving it a constructor argument, or changing what it
 * implements. None of those fails a build. What they produce is an SDK that reports nothing and says nothing,
 * in an app that did everything right.
 */
class TelemetryModuleDiscoveryTest {
    @Test
    fun theNameTheSessionLooksForResolvesToAUsableModule() {
        val found = Class.forName(TelemetryBootstrap.IMPLEMENTATION).getDeclaredConstructor().newInstance()

        assertTrue(found.javaClass.name, found is TelemetryBootstrap)
    }

    @Test
    fun thatNameIsThisModule() {
        assertEquals(TelemetryModule::class.java.name, TelemetryBootstrap.IMPLEMENTATION)
    }

    /**
     * The keep rule is what holds the two above through an integrator's R8, and it names the class in text.
     * A rename that updated the constant and the class but not the rule strips it from every release build.
     */
    @Test
    fun theKeepRuleNamesTheSameClass() {
        // From the source tree: the rule is packaged into the AAR for a consumer's R8, not onto this classpath.
        val rules = File("src/main/keepRules/rules.keep")
        assertTrue("${rules.absolutePath} is missing", rules.isFile)

        // The whole rule, not a prefix of it: `-keep class ...TelemetryModuleRenamed` contains the name of
        // `...TelemetryModule`, so a substring check passes for a class that was renamed out from under it.
        assertTrue(
            "no rule keeps ${TelemetryBootstrap.IMPLEMENTATION} and its constructor",
            rules.readText().lineSequence().any {
                it.trim() == "-keep class ${TelemetryBootstrap.IMPLEMENTATION} { <init>(); }"
            },
        )
    }
}
