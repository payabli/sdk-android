package com.payabli.buildlogic

import org.gradle.api.Action
import org.gradle.api.Task
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.publish.maven.tasks.AbstractPublishToMaven
import org.gradle.api.tasks.TaskContainer

/** The name of the setting, for a message that has to quote it. */
const val EXTRA_ENVIRONMENTS_SETTING: String = "payabli.sdk.extraEnvironments"

/**
 * Environments this build adds to the two committed in `PayabliEnvironment`.
 *
 * `name=origin`, comma separated, as `<name>=https://<host>.payabli.com`. Empty when nothing set it, which
 * is what a published artifact is built from, and the state a checkout of this repository is in.
 *
 * The Gradle property first, for a developer's own `~/.gradle/gradle.properties`, then the environment
 * variable, which is what an automated run uses because a `-P` value lands in the command line of the process
 * it is passed to. The same order and the same reason as [liveTestSettings].
 *
 * Here rather than in each build script because two of them read it and they must not disagree: `:core`
 * generates from it, and `payabli.publish` refuses to publish when it is set. A second spelling of the
 * property name in one of the two would leave an artifact carrying an environment nothing refused.
 */
fun extraEnvironmentsSetting(providers: ProviderFactory): Provider<String> =
    providers
        .gradleProperty(EXTRA_ENVIRONMENTS_SETTING)
        .orElse(providers.environmentVariable("PAYABLI_SDK_EXTRAENVIRONMENTS"))
        .map(String::trim)
        .orElse("")

/**
 * Fails every publish task while [setting] adds an environment.
 *
 * A published artifact carries only the committed environments. The setting is a build input, so the source
 * tree of an artifact built with it is identical to one built without, and a publish task is the only place
 * the difference can be caught.
 *
 * On the task rather than at configuration time, because a developer's build and a live run both set it
 * and both have to work; refusing at configuration would make the setting unusable for the thing it exists
 * for. [AbstractPublishToMaven] covers `publishToMavenLocal` as well as the remote repository, since a local
 * publish feeds a consuming build the same artifact.
 */
fun TaskContainer.refusePublishingWithExtraEnvironments(setting: Provider<String>) {
    withType(AbstractPublishToMaven::class.java).configureEach(AddExtraEnvironmentRefusal(setting))
}

private class AddExtraEnvironmentRefusal(
    private val setting: Provider<String>,
) : Action<AbstractPublishToMaven> {
    override fun execute(task: AbstractPublishToMaven) {
        task.doFirst(RefuseExtraEnvironments(setting))
    }
}

/**
 * A compiled [Action] rather than a lambda in the convention script.
 *
 * A lambda written in a `.gradle.kts` captures the script object, which the configuration cache cannot
 * serialize, and the whole build then reports a cache problem instead of the message this is here to give.
 */
private class RefuseExtraEnvironments(
    private val setting: Provider<String>,
) : Action<Task> {
    override fun execute(task: Task) {
        val value = setting.get()
        check(value.isEmpty()) {
            "$EXTRA_ENVIRONMENTS_SETTING is set to '$value'. A published artifact carries only the " +
                "committed environments. Unset it, and the Gradle daemon's environment with it, then publish."
        }
    }
}
