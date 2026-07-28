@file:OptIn(InternalSerializationApi::class)

package com.payabli.sdk.core.model

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.Serializable

/**
 * One field-level entry from a problem-details `errors` map.
 *
 * `@Serializable` and public, unlike the problem-detail body itself: the wire shape and the app-facing
 * shape are the same two strings, so a parallel DTO plus a copy step would buy nothing. It is a plain
 * value type rather than a `Throwable`, which is what makes annotating it safe.
 */
@Serializable
public class PayabliFieldError(
    public val message: String,
    public val suggestion: String? = null,
) {
    /** Server text; may echo the submitted value. Never logged. */
    override fun toString(): String = "PayabliFieldError(hasSuggestion=${suggestion != null})"
}
