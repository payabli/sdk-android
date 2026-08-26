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
            url = uri("https://maven.pkg.github.com/Fiserv/ch-ttp-androidsdk")
            content {
                includeGroup("com.fiserv.ch")
                includeGroup("com")
            }
            credentials {
                username = providers.gradleProperty("gpr.user").orNull
                    ?: System.getenv("GPR_USER")
                password = providers.gradleProperty("gpr.token").orNull
                    ?: System.getenv("GPR_TOKEN")
            }
        }
    }
}

// Only :taptopay needs this, so say so rather than leaving a bare 401.
if (providers.gradleProperty("gpr.user").orNull.isNullOrBlank() &&
    System.getenv("GPR_USER").isNullOrBlank()
) {
    logger.lifecycle(
        """
        Payabli: no credentials for the card reader registry. :taptopay will not resolve;
        every other module builds normally. Add to ~/.gradle/gradle.properties (not to this repo):
          gpr.user=<github-username>
          gpr.token=<classic PAT with read:packages>
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
