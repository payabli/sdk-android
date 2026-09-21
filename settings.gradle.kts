pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }

        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Card reader dependency. Requires a login; see CLAUDE.md for setup.
        maven {
            url = uri("https://sdk.payabli.com/maven")
            content {
                includeGroup("com.fiserv.ch")
                includeGroup("com")
            }
            credentials {
                username = providers.gradleProperty("payabli.maven.user").orNull
                    ?: System.getenv("PAYABLI_MAVEN_USER")
                password = providers.gradleProperty("payabli.maven.password").orNull
                    ?: System.getenv("PAYABLI_MAVEN_PASSWORD")
            }
            // Gradle withholds Basic credentials until it is challenged unless this is declared, so
            // every request would otherwise be sent twice: one 401 for each 200.
            authentication {
                create<BasicAuthentication>("basic")
            }
        }
    }
}

// Only :taptopay needs this, so say so rather than leaving a bare 401. Both halves are tested, because
// a password missing on its own reaches the same 401 this exists to explain.
val payabliMavenUser = providers.gradleProperty("payabli.maven.user").orNull
    ?: System.getenv("PAYABLI_MAVEN_USER")
val payabliMavenPassword = providers.gradleProperty("payabli.maven.password").orNull
    ?: System.getenv("PAYABLI_MAVEN_PASSWORD")
if (payabliMavenUser.isNullOrBlank() || payabliMavenPassword.isNullOrBlank()) {
    logger.lifecycle(
        """
        Payabli: no credentials for the card reader repository. :taptopay will not resolve;
        every other module builds normally. Add to ~/.gradle/gradle.properties (not to this repo):
          payabli.maven.user=<user>
          payabli.maven.password=<password>
        """.trimIndent(),
    )
}

rootProject.name = "PayabliSDK"
include(":example")
include(":core")
include(":payin")
include(":taptopay")
include(":telemetry")
include(":testutils")
include(":payabli-bom")
include(":payabli-android")
