package com.payabli.sdk.taptopay.attestation

import androidx.annotation.RestrictTo

/**
 * Which of Play Integrity's two request shapes a challenge is bound to.
 *
 * The two are not interchangeable and the difference is not cosmetic: they bind freshness through
 * different fields, they fail with **different error-code sets**, and one of them keeps a prepared
 * provider between calls while the other does not. Every attestation type here carries [this] so those
 * three differences stay explicit rather than being inferred from which object happens to be in hand.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public enum class VerdictClass {
    /** Freshness rides in `requestHash`, on a provider prepared ahead of the request. */
    STANDARD,

    /** Freshness rides in `nonce`, on a one-shot request that reaches Google's servers each time. */
    CLASSIC,
}
