package com.payabli.sdk.core.telemetry

import com.payabli.sdk.core.model.RedactedFailure

/**
 * The `failure_type` a report carries for [failure].
 *
 * @param failure what the reporting call caught.
 * @return the simple name of the type a redaction stands in for, when [failure] is one or carries one as its
 *   cause; otherwise [failure]'s own simple name. Never a message.
 */
internal fun failureTypeOf(failure: Throwable): String {
    val redaction = failure.takeIf { it is RedactedFailure } ?: failure.cause?.takeIf { it is RedactedFailure }
    val qualified = redaction?.message?.substringBefore(' ') ?: return failure.javaClass.simpleName
    return qualified.substringAfterLast('.').substringAfterLast('$')
}
