package com.payabli.example.app.demo.config

import java.io.File

/**
 * The value a `payabli.demo.*` setting falls back to, read out of the build file.
 *
 * `BuildConfig` carries what this run resolved, which a `-P` flag or a developer's own
 * `secrets.properties` changes, so it cannot answer what the default is. A test asserting a default
 * against `BuildConfig` instead passes or fails on who is running it.
 */
internal object BuildFileDefaults {
    /** Relative, because a Gradle test task runs with the module directory as its working directory. */
    private val buildFile = File("build.gradle.kts")

    /** Null when the setting has no `demoSetting` call, which is itself worth failing on. */
    fun of(key: String): String? =
        Regex("""demoSetting\("${Regex.escape(key)}", "([^"]*)"\)""")
            .find(buildFile.readText())
            ?.groupValues
            ?.get(1)

    /** Names the file, so a failure says where to look. */
    val location: String get() = buildFile.absolutePath
}
