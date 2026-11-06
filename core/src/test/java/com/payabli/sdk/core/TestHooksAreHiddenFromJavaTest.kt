package com.payabli.sdk.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kotlin `internal` is public on the JVM under a mangled name, so a Java caller can reach a test hook the Kotlin
 * surface hides. A synthetic method is one Java source cannot call.
 */
class TestHooksAreHiddenFromJavaTest {
    @Test
    fun `the session's test hooks cannot be called from Java`() {
        val hooks = listOf("initializeWith", "initializeAgainst", "reset")
        val methods =
            PayabliSession.Companion::class.java.declaredMethods
                .filter { method -> hooks.any { method.name.startsWith("$it$") || method.name == it } }

        assertEquals(methods.toString(), hooks.toSet(), methods.map { it.name.substringBefore('$') }.toSet())
        methods.forEach { assertTrue("${it.name} is callable from Java", it.isSynthetic) }
    }
}
