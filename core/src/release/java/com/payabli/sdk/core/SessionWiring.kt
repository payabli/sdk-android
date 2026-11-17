package com.payabli.sdk.core

/**
 * How a session is built, in the release build: nothing is replaced. Each member is `Nothing?`, so this copy
 * cannot hold anything but null, and [PRODUCTION] is the only instance.
 */
internal class SessionWiring private constructor() {
    val baseUrl: Nothing? get() = null
    val identifierOf: Nothing? get() = null
    val transport: Nothing? get() = null
    val decorations: Nothing? get() = null
    val authLock: Nothing? get() = null

    companion object {
        val PRODUCTION: SessionWiring = SessionWiring()
    }
}
