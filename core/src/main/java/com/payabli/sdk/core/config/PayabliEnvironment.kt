package com.payabli.sdk.core.config

import java.util.Collections

/**
 * The Payabli API environment. Determines the base URL every SDK request resolves against.
 *
 * A non-Payabli origin must not be reachable from shipped configuration, not even behind a debug flag, so
 * there is no developer or tunnel environment here. [SANDBOX] and [PRODUCTION] are the whole of what a
 * published artifact offers.
 *
 * A class with a private constructor rather than an enum, because the set has to be closed to an
 * integrator and open to the build. A build adds one through `payabli.sdk.extraEnvironments`, which
 * `core/build.gradle.kts` refuses unless it is an https `payabli.com` origin, and which
 * `payabli.publish` refuses outright, so nothing extra reaches a released artifact.
 *
 * Instances are created once, here, so equality is identity: two entries naming the same origin stay
 * distinct, which is what [entries] holding environments rather than URLs means.
 *
 * [baseUrl] is a `String` rather than a `java.net.URL` because `URL.equals` and `URL.hashCode` resolve
 * host names, which their own documentation calls a blocking operation.
 */
public class PayabliEnvironment private constructor(
    /**
     * The identifier, lowercase.
     *
     * One spelling for the runner argument, the Gradle property and the reported value, so none of the
     * three needs a table mapping it to the others.
     */
    public val name: String,
    /** Origin for this environment, with no trailing path. */
    public val baseUrl: String,
) {
    override fun toString(): String = name

    public companion object {
        @JvmField
        public val SANDBOX: PayabliEnvironment =
            PayabliEnvironment("sandbox", "https://api-sandbox.payabli.com")

        @JvmField
        public val PRODUCTION: PayabliEnvironment =
            PayabliEnvironment("production", "https://api.payabli.com")

        /**
         * Every environment this build offers.
         *
         * The two committed ones first, then whatever the build added. A build input appends and can do
         * nothing else: it cannot remove either of the two, and it cannot repoint one.
         *
         * Unmodifiable, and that is a runtime guarantee rather than a Kotlin one. `List` is read-only to a
         * Kotlin caller and nothing more: the backing object is an `ArrayList`, `@JvmField` publishes it as a
         * static field, and a Java caller or a Kotlin cast could empty it, after which [named] answers
         * nothing and every session that resolves an environment by name fails.
         */
        @JvmField
        public val entries: List<PayabliEnvironment> = listedWith(EXTRA_ENVIRONMENTS)

        /**
         * [entries] for a given list of added environments.
         *
         * Separate from [entries] because the added list is fixed at build time, so a test on a build that
         * added none cannot reach the appending at all: every assertion about ordering would pass on the
         * empty case and say nothing about the one that matters. Its production caller is [entries].
         */
        @JvmSynthetic
        internal fun listedWith(extra: List<Pair<String, String>>): List<PayabliEnvironment> =
            Collections.unmodifiableList(
                listOf(SANDBOX, PRODUCTION) + extra.map { (name, baseUrl) -> PayabliEnvironment(name, baseUrl) },
            )

        /** The environment [name] names, trimmed and case-insensitive, or null when nothing does. */
        @JvmStatic
        public fun named(name: String): PayabliEnvironment? {
            val wanted = name.trim()
            return entries.firstOrNull { it.name.equals(wanted, ignoreCase = true) }
        }
    }
}
