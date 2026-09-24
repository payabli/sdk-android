package com.payabli.buildlogic

import org.gradle.api.Action
import org.gradle.api.Task
import org.gradle.api.provider.Provider
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.api.tasks.TaskContainer

/**
 * Fails a publish task whose repository is not a directory.
 *
 * Resolved after configuration rather than during it, so a callback that turns the repository remote
 * after the convention plugin declared it is still caught. A check in `afterEvaluate` is not: a later
 * one reaches the same repository and the task then uploads.
 */
fun TaskContainer.refusePublishingToARemoteRepository() {
    withType(PublishToMavenRepository::class.java).configureEach(AddRemoteRepositoryRefusal())
}

private class AddRemoteRepositoryRefusal : Action<PublishToMavenRepository> {
    override fun execute(task: PublishToMavenRepository) {
        // Through a provider: maven-publish assigns the repository after creating the task, so it is
        // null while this runs, and the configuration cache discards the repository itself.
        task.doFirst(
            RefuseRemoteRepository(
                task.project.provider { task.repository?.name ?: "" },
                task.project.provider { task.repository?.url?.scheme ?: "file" },
            ),
        )
    }
}

/**
 * A compiled [Action] rather than a lambda in the convention script, for the reason
 * `RefuseExtraEnvironments` is one: a lambda written in a `.gradle.kts` captures the script object,
 * which the configuration cache cannot serialize.
 */
private class RefuseRemoteRepository(
    private val repository: Provider<String>,
    private val scheme: Provider<String>,
) : Action<Task> {
    override fun execute(task: Task) {
        check(scheme.get() == "file") {
            "payabli.publish: publishing repository '${repository.get()}' is ${scheme.get()}, and only " +
                "file is allowed. Artifacts reach the origin through the publishing workflow, which " +
                "uploads the staging tree; Gradle writes that tree and does not send it."
        }
    }
}
