package com.payabli.sdk.core.network.impl

/**
 * The declared decoration chain, applied to every outbound request in this order.
 *
 * **Empty on purpose.** A security header stamped with a placeholder value would establish that an empty
 * value is acceptable, so a decoration arrives only with the server contract it satisfies.
 *
 * Order is two segments, by convention rather than by type: **CONTRIBUTORS** add a header or body field,
 * then **BINDERS** digest or sign over what the contributors emitted, so a binder is always last.
 * `PayabliRequestDecorationsTest` pins the sequence, so a misplaced insertion fails the build.
 */
internal object PayabliRequestDecorations {
    /** Index 0 runs first. */
    internal val chain: List<PayabliRequestDecoration> =
        listOf(
            // -- CONTRIBUTORS -- (none yet)
            // -- BINDERS -- (none yet, and always last)
        )
}
