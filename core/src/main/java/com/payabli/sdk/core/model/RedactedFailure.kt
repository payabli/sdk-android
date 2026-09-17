package com.payabli.sdk.core.model

import androidx.annotation.RestrictTo

/**
 * A stand-in that has already taken a failure's message off, keeping its type and its frames.
 *
 * **A redaction happens once.** A carrier that wraps a failure has to tell a redaction that has already
 * happened from one it owes, and it cannot do that by looking: a redacted cause and an ordinary one are both
 * a `Throwable` carrying a class name. Redacting a second time reports the type of the stand-in rather than
 * the type that failed, and the frames of the site that built it, which is the whole diagnostic gone.
 *
 * Each module keeps its own implementation, because the class is three lines and sharing it would widen a
 * published module's surface to suit an internal one. What they share is this, which says only that the
 * message is already off.
 *
 * **Restricted**, like [leavesOutcomeUnknown]. Which failures this SDK's own carriers recognise is not a
 * host's to read.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public interface RedactedFailure
