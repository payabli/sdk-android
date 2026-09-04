package com.payabli.buildlogic

import org.gradle.api.Action
import org.gradle.api.Task
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.api.tasks.TaskContainer
import java.io.File

/**
 * The marker a notice carries while its vendor text is still missing.
 *
 * Matched rather than compared, so the surrounding wording can be edited without turning the guard off by
 * accident.
 */
const val NOTICE_PLACEHOLDER: String = "[FISERV LICENSE TEXT GOES HERE]"

/**
 * Fails a publish to a remote repository while [notice] still holds [NOTICE_PLACEHOLDER].
 *
 * The notice ships inside the module's own AAR, so publishing it with the placeholder in place
 * distributes an artifact that names a proprietary component and then does not say under what terms. The
 * text is a legal deliverable and cannot be written here, which is exactly why this is a build guard
 * rather than a note: the module is withheld until somebody replaces the marker, and it stops being
 * withheld the moment they do, with nothing else to remember.
 *
 * [PublishToMavenRepository] and not `AbstractPublishToMaven`, which is the difference from
 * [refusePublishingWithExtraEnvironments]. A local publish stays allowed so the module can still be built
 * against and tested; what must not happen is the artifact leaving the machine.
 */
fun TaskContainer.refusePublishingWithNoticePlaceholder(notice: File) {
    withType(PublishToMavenRepository::class.java).configureEach(AddNoticeRefusal(notice))
}

private class AddNoticeRefusal(
    private val notice: File,
) : Action<PublishToMavenRepository> {
    override fun execute(task: PublishToMavenRepository) {
        task.doFirst(RefuseNoticePlaceholder(notice))
    }
}

/**
 * A compiled [Action] rather than a lambda in the convention script, for the reason recorded on
 * [refusePublishingWithExtraEnvironments]'s: a lambda written in a `.gradle.kts` captures the script
 * object, which the configuration cache cannot serialize.
 */
private class RefuseNoticePlaceholder(
    private val notice: File,
) : Action<Task> {
    override fun execute(task: Task) {
        if (!notice.isFile) return
        check(!notice.readText().contains(NOTICE_PLACEHOLDER)) {
            "${notice.name} still contains $NOTICE_PLACEHOLDER, and it ships inside the published " +
                "artifact. The vendor's attribution text has to replace it before this module is " +
                "published anywhere. Publishing to Maven local is unaffected."
        }
    }
}
