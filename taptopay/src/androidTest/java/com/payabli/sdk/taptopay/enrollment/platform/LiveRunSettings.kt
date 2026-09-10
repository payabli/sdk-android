package com.payabli.sdk.taptopay.enrollment.platform

import androidx.test.platform.app.InstrumentationRegistry
import com.payabli.sdk.core.config.PayabliEnvironment

/**
 * The values a live run needs, read from runner arguments.
 *
 * **Absent is a failure, never a skip.** A skip here would go quiet the moment someone stopped passing a
 * property, and a standing skip reads exactly like a test that has always been fine. The message names the
 * Gradle property so the fix is the next thing the reader does.
 *
 * [entry] and [environment] are passed per run and neither belongs in `~/.gradle/gradle.properties`: they
 * name the paypoint and the deployment a run is aimed at, and a file makes the next run inherit that aim
 * without saying so. [tokenEndpoint] is an override with a default, so the ordinary invocation omits it.
 *
 * No bearer is among them. [accessToken] fetches one from the token server on each call, which is what keeps
 * a long sequence off a single expiring token.
 */
internal object LiveRunSettings {
    /** The paypoint every call is scoped to. */
    val entry: String get() = required("entry", "payabli.ttp.entry")

    /**
     * Where a fresh bearer comes from. Defaults to the token server reached over `adb reverse`.
     *
     * A token, not a token *value*, because the ones this server issues are short-lived and a run pinned to
     * one fails partway through as `TOKEN_EXPIRED`.
     *
     * The bearer needs `tools_init` for the challenge and `pos_create` for everything else. The service
     * pins the exact token used at `/attest` into the attestation row, and `/activate` looks that row up by
     * it, so the SDK holding one token across the sequence is what makes activation work. Minting the
     * activation code uses the same one: `/activate/challenge` takes `pos_create` too. In production that
     * call comes from the merchant's backend under its own credential.
     */
    val tokenEndpoint: String
        get() =
            InstrumentationRegistry.getArguments().getString("tokenEndpoint")
                ?: LocalTokenServer.DEFAULT_ENDPOINT

    /** A fresh bearer. */
    fun accessToken(): String = LocalTokenServer.fetch(tokenEndpoint)

    /**
     * Which deployment the run talks to.
     *
     * Named per run like everything else here, rather than defaulted. A default sends a run that named no
     * environment at a real paypoint on whichever one it happened to be, and the paypoints these tests use
     * are not all on the same deployment, so the wrong one reads as a device the service has never heard of.
     *
     * The session and the code-minter both derive their host from this one value, so they cannot end up on
     * different deployments.
     */
    val environment: PayabliEnvironment
        get() {
            val name = required("environment", "payabli.ttp.environment")
            val named =
                PayabliEnvironment.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
                    ?: error("payabli.ttp.environment must be one of ${PayabliEnvironment.entries.joinToString()}")
            // This tier opens and closes real transactions, and one of its readers is a stub that answers a
            // tap with a captured response nobody presented a card for. Refused here rather than in each
            // test, so a class added later cannot reach production by not thinking about it.
            //
            // By host, not by identity. A build adds environments through `payabli.sdk.extraEnvironments`,
            // which refuses a name the SDK already carries but not an origin it already carries, so
            // `qa=https://api.payabli.com` is a distinct instance pointing at the same place. The port is
            // dropped as well, since `:443` names the same host.
            check(named.host() != PayabliEnvironment.PRODUCTION.host()) {
                "the live tier moves money and must not be pointed at production"
            }
            return named
        }

    /** The base URL, from [environment]. */
    val baseUrl: String get() = environment.baseUrl.trimEnd('/')

    /** The origin's host, with any scheme, port and path removed. */
    private fun PayabliEnvironment.host(): String =
        baseUrl
            .substringAfter("://")
            .substringBefore('/')
            .substringBefore(':')
            .lowercase()

    private fun required(
        argument: String,
        property: String,
    ): String =
        InstrumentationRegistry.getArguments().getString(argument)
            ?: error("$property is required for the live tier; pass -P$property=<value> on the command line")
}
