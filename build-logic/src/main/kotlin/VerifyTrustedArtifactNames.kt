import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Fails when a file this project resolves is named like a sources or javadoc jar.
 *
 * `gradle/verification-metadata.xml` trusts those names unchecked so an IDE can download them, and the
 * publisher chooses the name, so the build must never resolve one.
 */
abstract class VerifyTrustedArtifactNames : DefaultTask() {
    /** Every file the project's configurations and its build script's configurations resolve. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val resolved: ConfigurableFileCollection

    @TaskAction
    fun verify() {
        val trusted = resolved.files.map { it.name }.filter { TRUSTED_NAME.matches(it) }.sorted()
        if (trusted.isNotEmpty()) {
            throw GradleException(
                "These resolved files match a trusted-artifacts rule in gradle/verification-metadata.xml, " +
                    "so dependency verification never checked them:\n" +
                    trusted.joinToString("\n") { "  $it" },
            )
        }
    }

    companion object {
        /** The two `<trust file=…>` patterns in `gradle/verification-metadata.xml`. */
        val TRUSTED_NAME = Regex(".*-(sources|javadoc)[.]jar")
    }
}
