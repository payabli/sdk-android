package com.payabli.sdk.taptopay

import com.payabli.sdk.core.device.CardPresentLinkage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The present half of the linkage answer, which only this module's classpath can give.
 *
 * Every part of the lookup can be broken by an edit that looks harmless: renaming the marker, moving its
 * package, or dropping the keep rule. None of those fails a build. What they produce is a card-present app
 * reporting no device type, which reads as a card-not-present app.
 */
class CardPresentModuleDiscoveryTest {
    @Test
    fun theNameCoreLooksForResolvesHere() {
        assertTrue(CardPresentLinkage.MODULE, CardPresentLinkage.isLinked())
    }

    @Test
    fun thatNameIsThisModulesMarker() {
        assertEquals(CardPresentModule::class.java.name, CardPresentLinkage.MODULE)
    }

    /**
     * The keep rule holds the two above through an integrator's R8, and it names the class in text. A rename
     * that updated the constant and the class but not the rule strips it from every release build.
     */
    @Test
    fun theKeepRuleNamesTheSameClass() {
        // From the source tree: the rule is packaged into the AAR for a consumer's R8, not onto this classpath.
        val rules = File("src/main/keepRules/rules.keep")
        assertTrue("${rules.absolutePath} is missing", rules.isFile)

        // The whole rule, not a prefix of it: a rule naming `...CardPresentModuleRenamed` contains the name
        // of `...CardPresentModule`, so a substring check passes for a class renamed out from under it.
        assertTrue(
            "no rule keeps ${CardPresentLinkage.MODULE}",
            rules.readText().lineSequence().any { it.trim() == "-keep class ${CardPresentLinkage.MODULE}" },
        )
    }
}
