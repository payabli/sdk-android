package com.payabli.sdk.core.device

import androidx.annotation.RestrictTo

/**
 * Whether this app linked the card-present artifact, which is whether it can hold a device record at all.
 *
 * The one question that decides whether a reported device type is a fact or a guess. A phone that never
 * linked card-present cannot register a device, so it has no type in the service's vocabulary, and naming one
 * would be an unverifiable claim in the field designed for verifiable ones — a count of point-of-sale
 * reporters that included phones which cannot tap, with nothing in the record to say which were which.
 *
 * **A build-time fact, so the answer is fixed for the process.** That is what lets the device facts stay a
 * snapshot taken at install rather than something re-read per event.
 *
 * Looked up by name for the reason the reporting module is: the dependency cannot run the other way. `:core`
 * is depended on by every capability and names none of them as a dependency.
 *
 * Reachable across artifacts because the card-present module's own test is the only place the positive case
 * can be asserted: whether this resolves is decided by which module's classpath is running.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public object CardPresentLinkage {
    /**
     * The class this looks for.
     *
     * Named here rather than in the card-present module so the two cannot drift, and that module's own test
     * asserts this string resolves there while its keep rule holds the class through an integrator's R8.
     */
    public const val MODULE: String = "com.payabli.sdk.taptopay.CardPresentModule"

    @Volatile
    private var linked: Boolean? = null

    /** Answers once. The classpath cannot change while the process lives. */
    public fun isLinked(): Boolean =
        linked ?: synchronized(this) {
            linked ?: resolves(MODULE).also { linked = it }
        }

    /**
     * Whether [name] is on this classpath.
     *
     * Takes the name so a test can ask about a class it controls: the answer for [MODULE] is decided by which
     * module's tests are running, which makes it exactly the thing a single test cannot vary.
     */
    public fun resolves(name: String): Boolean =
        try {
            Class.forName(name)
            true
        } catch (_: ClassNotFoundException) {
            // The ordinary case for an app that takes card-not-present payments only.
            false
        } catch (_: LinkageError) {
            // A partly linked artifact, which is what a class kept while something it needs was stripped
            // looks like. Reported as absent: what this answers is whether a device record can exist, and
            // for a half-present module it cannot.
            false
        }
}
