package com.payabli.sdk.core.device

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The absent half of the answer.
 *
 * This module depends on nothing first-party, so its own classpath is a card-not-present app's, and that is
 * what makes the negative case real here rather than mocked. The present half cannot be asserted from this
 * module at all and lives in the card-present module's own test.
 */
class CardPresentLinkageTest {
    @Test
    fun withoutTheCardPresentArtifactThereIsNoDeviceType() {
        assertFalse(CardPresentLinkage.MODULE, CardPresentLinkage.isLinked())
    }

    /**
     * That the mechanism works at all, so the test above cannot pass by being broken.
     *
     * A lookup that returned false for everything would satisfy the negative case forever, and every app
     * would silently report no device type.
     */
    @Test
    fun aClassThatIsOnThisClasspathResolves() {
        assertTrue(CardPresentLinkage.resolves(CardPresentLinkageTest::class.java.name))
    }

    @Test
    fun aClassThatIsNotOnAnyClasspathDoesNotResolve() {
        assertFalse(CardPresentLinkage.resolves("com.payabli.sdk.core.device.NoSuchClassAnywhere"))
    }
}
