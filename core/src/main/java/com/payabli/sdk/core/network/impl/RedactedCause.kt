package com.payabli.sdk.core.network.impl

/**
 * Stands in for a failure whose own message cannot be allowed out, keeping the type and the stack
 * trace and dropping the text.
 *
 * `kotlinx.serialization` appends the input it could not parse to its message, so a decoding failure
 * carries the response body verbatim. `PayabliException.message` is deliberately only the error code,
 * but a cause defeats that on its own: crash reporters and `printStackTrace` render the whole chain,
 * and the host app's reporter is outside anything this SDK scrubs.
 *
 * The class name is kept as the message because a type name carries no subject, and the original stack
 * trace is kept because a class, method, file and line are the whole diagnostic value. The cause chain
 * stops here.
 */
internal class RedactedCause(
    original: Throwable,
) : Exception(original.javaClass.name) {
    init {
        stackTrace = original.stackTrace
    }
}
