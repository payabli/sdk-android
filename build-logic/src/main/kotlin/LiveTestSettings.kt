package com.payabli.buildlogic

import org.gradle.api.provider.ProviderFactory

/**
 * What a live run against a real environment needs on the device, or null when it is not one.
 *
 * **No client credential is here, and that is the point.** A token is minted by the app's own backend from a
 * client id and secret, and the device never holds either: it asks that server and receives a token. A test
 * driving the real thing has to obey the same boundary, so what crosses is the address of a token server, the
 * entry point the app is configured with, and the environment.
 *
 * Shared by `:payin` and `:example`, which pass the same three and would otherwise drift on what counts as a
 * usable set, because one run configures both.
 *
 * Each value comes from its Gradle property first and its environment variable second. A `-P` value is an
 * argument, so it lands in the command line of the process it is passed to; an environment variable does not,
 * which is what an automated run uses. The property stays because a developer's own values belong in
 * `~/.gradle/gradle.properties`.
 *
 * All three or none: a partly-set list stops configuration rather than reading as absent. Read as absent, a
 * run that meant to reach a real environment silently takes the path for one that did not, and fails much
 * later with a symptom naming neither the missing value nor the fallback it took.
 *
 * Null rather than a map of nullable values, so a caller that gets a map has three settings and not three
 * more places nullability might arise.
 */
fun liveTestSettings(providers: ProviderFactory): Map<String, String>? {
    val values =
        LIVE_TEST_VARIABLES.mapValues { (property, variable) ->
            providers.gradleProperty("payabli.liveTest.$property").orNull.usable()
                ?: providers.environmentVariable(variable).orNull.usable()
        }

    val missing = values.filterValues { it == null }.keys
    if (missing.isNotEmpty() && missing.size < values.size) {
        error("payabli.liveTest.* is partly set. Missing: ${missing.sorted().joinToString()}")
    }
    if (missing.isNotEmpty()) return null
    return values.mapValues { (name, value) -> requireNotNull(value) { "payabli.liveTest.$name" } }
}

/**
 * Trimmed, and blank read as absent.
 *
 * `-Ppayabli.liveTest.tokenHost=` and an environment variable set to nothing both arrive as "", which counts
 * as present and would satisfy the check above while producing an address that is a scheme and a path. The
 * sample app's own `demoSetting` guards its settings the same way and for the same reason.
 */
private fun String?.usable(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

private val LIVE_TEST_VARIABLES =
    mapOf(
        "environment" to "PAYABLI_LIVETEST_ENVIRONMENT",
        "entryPoint" to "PAYABLI_LIVETEST_ENTRY_POINT",
        "tokenHost" to "PAYABLI_LIVETEST_TOKEN_HOST",
    )
