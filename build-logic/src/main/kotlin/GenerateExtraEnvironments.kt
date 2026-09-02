package com.payabli.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.net.URI
import java.net.URISyntaxException

/**
 * Writes the list `PayabliEnvironment` appends to its two committed entries.
 *
 * Beside [extraEnvironmentsSetting] rather than in `:core`'s build script, because parsing a setting and
 * defining it are one thing and a module script that carries both is where the rules stop being findable.
 *
 * Every value is refused here rather than where it is read. A bad one that reaches Kotlin is a compile error
 * naming a generated file, which says nothing about the setting that produced it.
 *
 * The origin rule is the one `PayabliEnvironment`'s own doc states: a non-Payabli origin must not be
 * reachable from configuration, and a build setting is configuration. An origin is a scheme, a host and a
 * port, so user info, a path, a query and a fragment are each refused as not being part of one.
 *
 * Refusing them is also what keeps the generated string safe to write unquoted. A dollar sign inside a Kotlin
 * string literal is interpolation, so an origin carrying `?$name` in a query would reach the generated file
 * as one and the promised setting error would arrive instead as a compile error in a file nobody wrote.
 */
abstract class GenerateExtraEnvironments : DefaultTask() {
    @get:Input
    abstract val setting: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val entries = parse(setting.get())
        val body =
            if (entries.isEmpty()) {
                "emptyList()"
            } else {
                entries.joinToString(separator = "\n", prefix = "listOf(\n", postfix = "\n)") {
                    "    \"${it.first}\" to \"${it.second}\","
                }
            }
        val file = outputDirectory.get().asFile.resolve(GENERATED_PATH)
        file.parentFile.mkdirs()
        file.writeText(
            "package com.payabli.sdk.core.config\n\n" +
                "// Generated from $EXTRA_ENVIRONMENTS_SETTING. Empty in a published build, which\n" +
                "// payabli.publish enforces. See PayabliEnvironment.\n" +
                "internal val EXTRA_ENVIRONMENTS: List<Pair<String, String>> = $body\n",
        )
    }

    private fun parse(setting: String): List<Pair<String, String>> {
        val entries =
            setting
                .split(",")
                .map(String::trim)
                .filter(String::isNotEmpty)
                .map { entry ->
                    val separator = entry.indexOf('=')
                    require(separator > 0) { "$EXTRA_ENVIRONMENTS_SETTING: '$entry' is not name=origin" }
                    entry.substring(0, separator).trim() to entry.substring(separator + 1).trim()
                }

        entries.forEach { (name, origin) ->
            require(NAME.matches(name)) {
                "$EXTRA_ENVIRONMENTS_SETTING: '$name' is not a lowercase identifier"
            }
            require(name !in COMMITTED) {
                "$EXTRA_ENVIRONMENTS_SETTING: '$name' is committed and cannot be added"
            }
            // The one failure a malformed setting produces, and nothing else. A `runCatching` here would
            // catch `Throwable`, so a linkage error or a programming mistake becomes a message blaming
            // the setting.
            val uri =
                try {
                    URI(origin)
                } catch (invalid: URISyntaxException) {
                    null
                }
            require(uri != null && uri.scheme == "https") {
                "$EXTRA_ENVIRONMENTS_SETTING: '$origin' is not an https origin"
            }
            require(uri.host.orEmpty().endsWith(".payabli.com")) {
                "$EXTRA_ENVIRONMENTS_SETTING: '$origin' is not a payabli.com origin"
            }
            require(uri.userInfo == null) { "$EXTRA_ENVIRONMENTS_SETTING: '$origin' carries user info" }
            // `URI` range-checks nothing: it reads `:65536` as a port and hands it over, and the value
            // survives every other rule here. What refuses it is the socket, with `port out of range`, on
            // the first request of a run that had already been configured and started.
            require(uri.port == -1 || uri.port in 1..65535) {
                "$EXTRA_ENVIRONMENTS_SETTING: '$origin' carries a port outside 1 to 65535"
            }
            require(uri.path.isNullOrEmpty()) { "$EXTRA_ENVIRONMENTS_SETTING: '$origin' carries a path" }
            require(uri.query == null) { "$EXTRA_ENVIRONMENTS_SETTING: '$origin' carries a query" }
            require(uri.fragment == null) { "$EXTRA_ENVIRONMENTS_SETTING: '$origin' carries a fragment" }
        }

        val names = entries.map { it.first }
        require(names.size == names.toSet().size) {
            "$EXTRA_ENVIRONMENTS_SETTING: names repeat: ${names.sorted()}"
        }
        return entries
    }

    private companion object {
        const val GENERATED_PATH = "com/payabli/sdk/core/config/ExtraEnvironments.kt"
        val NAME = Regex("^[a-z][a-z0-9]*$")

        /** The two in `PayabliEnvironment` itself. Mirrored, and the message says what a mismatch means. */
        val COMMITTED = setOf("sandbox", "production")
    }
}
