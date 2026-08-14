package com.payabli.sdk.taptopay.enrollment.platform

import androidx.test.platform.app.InstrumentationRegistry
import com.payabli.sdk.core.config.PayabliEnvironment

/**
 * The four values a live run needs, read from runner arguments.
 *
 * **Absent is a failure, never a skip.** A skip here would go quiet the moment someone stopped passing a
 * property, and a standing skip reads exactly like a test that has always been fine. The message names the
 * Gradle property so the fix is the next thing the reader does.
 *
 * All four are passed per run and none belongs in `~/.gradle/gradle.properties`: two are bearer tokens with
 * short lives, and putting them in a file makes them outlive the run that needed them.
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
     * Which deployment the run talks to. Defaults to the one the test paypoints live on.
     *
     * The session and the code-minter both derive their host from this one value, so they cannot end up on
     * different deployments. That mismatch surfaces as a device the service has never heard of.
     */
    val environment: PayabliEnvironment
        get() =
            InstrumentationRegistry.getArguments().getString("environment")?.let { name ->
                PayabliEnvironment.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
                    ?: error("payabli.ttp.environment must be one of ${PayabliEnvironment.entries.joinToString()}")
            } ?: PayabliEnvironment.QA

    /** The base URL, from [environment]. */
    val baseUrl: String get() = environment.baseUrl.trimEnd('/')

    private fun required(
        argument: String,
        property: String,
    ): String =
        InstrumentationRegistry.getArguments().getString(argument)
            ?: error("$property is required for the live tier; pass -P$property=<value> on the command line")
}
