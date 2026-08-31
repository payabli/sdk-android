package com.payabli.example.app.sdk

import com.payabli.example.app.BuildConfig
import com.payabli.sdk.core.config.PayabliEnvironment

/**
 * A Payabli environment, as the rest of this app names it.
 *
 * Here rather than in `demo/config` because it wraps an SDK type, and `sdk` is the only package that may
 * name one. What it hands the app is a label and a host, which is all a screen shows.
 *
 * It holds the SDK's environment and derives everything from it, so no origin is written here. The SDK owns
 * every base URL, and a second copy in the sample drifts from what a request actually resolves against.
 */
data class DemoEnvironment internal constructor(
    internal val sdkEnvironment: PayabliEnvironment,
) {
    /** What the setting spells and a screen shows. */
    val label: String get() = sdkEnvironment.name

    /** Origin for this environment, with no trailing path. */
    val baseUrl: String get() = sdkEnvironment.baseUrl

    /** The host alone, which is what a detail row has room for. */
    val host: String get() = baseUrl.removePrefix("https://")

    override fun toString(): String = label

    companion object {
        val SANDBOX: DemoEnvironment = DemoEnvironment(PayabliEnvironment.SANDBOX)
        val PRODUCTION: DemoEnvironment = DemoEnvironment(PayabliEnvironment.PRODUCTION)

        /**
         * Every environment this build offers: the two above, then whatever `payabli.demo.extraEnvironments`
         * added, in the order it named them.
         *
         * It appends, as the SDK's own setting does, so the picker always offers the two and no value here
         * empties it. A name the SDK carries no environment for is dropped rather than invented: a picker
         * entry that resolves to no origin is worse than one that is absent, and it is what a build asking
         * for an environment its `:core` was not built with would otherwise get.
         */
        val offered: List<DemoEnvironment> =
            (
                listOf(PayabliEnvironment.SANDBOX, PayabliEnvironment.PRODUCTION) +
                    BuildConfig.DEMO_EXTRA_ENVIRONMENTS
                        .split(",")
                        .map(String::trim)
                        .filter(String::isNotEmpty)
                        .mapNotNull(PayabliEnvironment::named)
            ).distinct().map(::DemoEnvironment)

        /** The one configured by default, and what an unrecognised setting falls back to. */
        val DEFAULT: DemoEnvironment = offered.first()

        /** Every offered label, for a message that has to list them. */
        val labels: String get() = offered.joinToString(", ") { it.label }

        /**
         * The offered environment [label] names, or null when none does.
         *
         * Trimmed and case-insensitive, for a value typed by hand into a properties file or a `-P` flag. An
         * environment the SDK carries but this build does not offer is not a match: the picker and this have
         * to answer the same question.
         */
        fun named(label: String): DemoEnvironment? {
            val wanted = label.trim()
            return offered.firstOrNull { it.label.equals(wanted, ignoreCase = true) }
        }
    }
}
