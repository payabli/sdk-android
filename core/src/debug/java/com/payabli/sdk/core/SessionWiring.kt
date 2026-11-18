package com.payabli.sdk.core

import com.payabli.sdk.core.network.PayabliTransport
import com.payabli.sdk.core.network.impl.AuthFailureListener
import com.payabli.sdk.core.network.impl.PayabliRequestDecoration
import kotlinx.coroutines.sync.Mutex

/**
 * How a session is built, in the debug build, where the SDK's own tests can replace part of it. A member left
 * null takes the production choice, and [PRODUCTION] leaves all of them null. The release build's copy has the
 * same members and no way to set them.
 */
internal class SessionWiring(
    val baseUrl: String? = null,
    val transport: (suspend (AuthFailureListener) -> PayabliTransport)? = null,
    val decorations: List<PayabliRequestDecoration>? = null,
    val authLock: Mutex? = null,
) {
    companion object {
        val PRODUCTION: SessionWiring = SessionWiring()
    }
}
