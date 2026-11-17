package com.payabli.sdk.core

import android.content.Context
import com.payabli.sdk.core.network.PayabliTransport
import com.payabli.sdk.core.network.impl.AuthFailureListener
import com.payabli.sdk.core.network.impl.PayabliRequestDecoration
import kotlinx.coroutines.sync.Mutex

/**
 * How a session is built, in the debug build, where the SDK's own tests can replace part of it. A member left
 * null takes the production choice, and [PRODUCTION] leaves all of them null. The release build's copy has the
 * same members and no way to set them.
 *
 * @property baseUrl the origin every request goes to, in place of the configured environment's.
 * @property identifierOf reads the platform identifier, so a test can give a real device none.
 * @property transport builds the session's transport, which then renews no refused token.
 * @property decorations the request chain, in place of the one built from the auth holder.
 * @property authLock the lock an auth holder takes, so a test can hold it.
 */
internal class SessionWiring(
    val baseUrl: String? = null,
    val identifierOf: ((Context) -> String)? = null,
    val transport: (suspend (AuthFailureListener) -> PayabliTransport)? = null,
    val decorations: List<PayabliRequestDecoration>? = null,
    val authLock: Mutex? = null,
) {
    companion object {
        val PRODUCTION: SessionWiring = SessionWiring()
    }
}
