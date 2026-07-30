package com.payabli.sdk.core.network.impl

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Carries the token the chain actually stamped back to the caller that must report it as rejected.
 *
 * The wrapper cannot just remember what it read: the chain reads again, so a rotation in between makes the
 * two disagree, and reporting the remembered one takes the already-rotated branch and replays a credential the
 * server just refused. Mirrors `PayabliAuth`'s `RefreshInProgress`, which passes a fact down a context the
 * same way.
 *
 * One instance per attempt, so concurrent requests cannot see each other's.
 */
internal class SentToken : AbstractCoroutineContextElement(Key) {
    @Volatile
    var value: String? = null

    companion object Key : CoroutineContext.Key<SentToken>
}
