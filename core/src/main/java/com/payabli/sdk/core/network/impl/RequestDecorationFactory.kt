package com.payabli.sdk.core.network.impl

import com.payabli.sdk.core.auth.PayabliAuth

/**
 * The chain applied to every outbound request, in order: **CONTRIBUTORS** add a header or body field, then
 * **BINDERS** sign over what they emitted, so a binder is always last. `RequestDecorationFactoryTest` pins
 * the sequence.
 *
 * A function rather than a value so [PayabliService.create] can take the auth holder without taking a chain.
 */
internal object RequestDecorationFactory {
    internal fun chainFor(auth: PayabliAuth): List<PayabliRequestDecoration> =
        listOf(
            // -- CONTRIBUTORS --
            CorrelationDecoration(),
            BearerDecoration(auth),
            JsonBodyDecoration(),
            // -- BINDERS -- (none yet, and always last)
        )
}
