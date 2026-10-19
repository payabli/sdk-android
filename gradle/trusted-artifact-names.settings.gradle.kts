// verification-metadata.xml trusts sources and javadoc jars unchecked. A build script loads its classpath
// before any task runs, so the check for those names happens as that classpath resolves.
val trustedName = Regex(".*-(sources|javadoc)[.]jar")

gradle.beforeProject {
    val projectPath = path
    buildscript.configurations.configureEach {
        val configurationName = name
        incoming.afterResolve {
            val trusted = artifacts.artifacts.map { it.file.name }.filter { trustedName.matches(it) }.sorted()
            if (trusted.isNotEmpty()) {
                throw GradleException(
                    "The build script classpath '$configurationName' of $projectPath resolves files that " +
                        "dependency verification trusts without checking:\n" +
                        trusted.joinToString("\n") { "  $it" },
                )
            }
        }
    }
}
