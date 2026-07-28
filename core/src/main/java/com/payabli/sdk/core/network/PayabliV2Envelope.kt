// IDE-only workaround, not a compiler requirement: K2's IDE analysis flags the plugin-generated
// $serializer as needing this opt-in, while the compiler exempts it (KTIJ-31549). Remove when fixed.
@file:OptIn(InternalSerializationApi::class)

package com.payabli.sdk.core.network

import androidx.annotation.RestrictTo
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// File-private rather than in a companion: the serialization plugin generates the companion that
// holds serializer(), so declaring a private one here would make serializer() inaccessible.
private const val APPROVED_PREFIX = "A"
private const val DECLINED_PREFIX = "D"

/**
 * The v2 response envelope used by the MoneyIn APIs.
 *
 * ```json
 * { "code": "A...", "reason": "...", "explanation": "...", "action": "...", "data": { ... } }
 * ```
 *
 * Distinct from [PayabliEnvelope], which wraps the legacy device routes and reports business
 * failures as HTTP 200. Do not conflate them.
 *
 * The SDK ignores any envelope-level `token` field: every authenticated request reuses the access
 * token the session already holds.
 */
@Serializable
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public class PayabliV2Envelope<T>(
    /** The only field the server always sends. A body without it is a decode failure. */
    public val code: String,
    public val reason: String? = null,
    public val explanation: String? = null,
    public val action: String? = null,
    @SerialName("data")
    public val payload: T? = null,
) {
    /** Approved family. */
    public val isApproved: Boolean get() = code.startsWith(APPROVED_PREFIX)

    /** Declined family. */
    public val isDeclined: Boolean get() = code.startsWith(DECLINED_PREFIX)

    /** Never includes [payload] or [reason]: both may echo request data. */
    override fun toString(): String = "PayabliV2Envelope(code=$code)"
}
