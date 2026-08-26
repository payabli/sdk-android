package com.payabli.sdk.taptopay.adapters

/** What the reader failed at, in the terms the session and the charge act on. */
internal sealed class CardReaderException(
    message: String,
    cause: Throwable?,
) : Exception(message, cause) {
    /** [message] names the field and never its value: two of them are the vendor's live API secrets. */
    class CredentialsUnusable(
        message: String,
    ) : CardReaderException(message, null)

    /**
     * The reader did not come up. The same call may succeed later.
     *
     * The message carries the vendor's code where there is one: it is the only part of a refusal that
     * tells one apart from another, and a host reporting the failure has nothing else to name.
     */
    class ArmingFailed(
        cause: Throwable?,
    ) : CardReaderException(armingMessage(cause), cause)

    /** The reader session is gone. Bringing the reader up again is the only repair. */
    class SessionUnusable(
        cause: Throwable?,
    ) : CardReaderException("the reader session is not usable", cause)

    /** The tap did not produce a payment. The session it ran on is unaffected. */
    class ReadFailed(
        cause: Throwable?,
    ) : CardReaderException("the tap did not complete", cause)
}

private fun armingMessage(cause: Throwable?): String {
    val code = (cause as? CardReaderFailure)?.code
    return if (code == null) "the reader did not come up" else "the reader did not come up (code $code)"
}
