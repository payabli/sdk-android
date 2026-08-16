import org.gradle.api.provider.ProviderFactory

/**
 * The four values a live run against a real environment needs, or nulls when it is not one.
 *
 * Shared by `:payin` and `:example`, which resolve the same four and would otherwise drift: the property
 * names, the variable names and what counts as a usable set have to agree, because one set of credentials
 * feeds both modules in the same job.
 *
 * Each value comes from its Gradle property first and its environment variable second. A `-P` value is an
 * argument, so it lands in the process command line; an environment variable does not, which is what an
 * automated run uses. The property stays because a developer's own values belong in
 * `~/.gradle/gradle.properties`.
 */
fun liveTestCredentials(providers: ProviderFactory): Map<String, String?> =
    LIVE_TEST_VARIABLES.mapValues { (property, variable) ->
        providers.gradleProperty("payabli.liveTest.$property").orNull
            ?: providers.environmentVariable(variable).orNull
    }

/**
 * True when all four are present, false when none are, and an error in between.
 *
 * A partly-set list is refused rather than treated as absent. Read as absent, a run that meant to reach a
 * real environment silently takes the path for one that did not, and fails much later with a symptom that
 * names neither the missing value nor the fallback it took.
 */
fun liveTestCredentialsUsable(values: Map<String, String?>): Boolean {
    val missing = values.filterValues { it == null }.keys
    if (missing.isNotEmpty() && missing.size < values.size) {
        error("payabli.liveTest.* is partly set. Missing: ${missing.sorted().joinToString()}")
    }
    return missing.isEmpty()
}

private val LIVE_TEST_VARIABLES =
    mapOf(
        "environment" to "PAYABLI_LIVETEST_ENVIRONMENT",
        "entryPoint" to "PAYABLI_LIVETEST_ENTRY_POINT",
        "clientId" to "PAYABLI_LIVETEST_CLIENT_ID",
        "clientSecret" to "PAYABLI_LIVETEST_CLIENT_SECRET",
    )
