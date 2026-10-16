package com.payabli.sdk.core.network

import androidx.annotation.RestrictTo
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Set once a request in this coroutine is handed to the HTTP layer, after which it may have reached the
 * service. A key holder installs one around a payment request and reads it if that request is cancelled.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public class PayabliSendMark : AbstractCoroutineContextElement(Key) {
    private val marked = AtomicBoolean(false)

    /** Whether a request may have reached the service since this mark was installed. */
    public val mayHaveLeft: Boolean get() = marked.get()

    /** Records the hand-off. A transport that sends calls it before the request leaves the device. */
    public fun mark() {
        marked.set(true)
    }

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public companion object Key : CoroutineContext.Key<PayabliSendMark>
}
