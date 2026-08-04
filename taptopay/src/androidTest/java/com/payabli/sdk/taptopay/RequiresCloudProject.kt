package com.payabli.sdk.taptopay

/**
 * Marks a test needing a real Play Integrity project number, **excluded from the run** when there is none.
 *
 * Excluded by the build, not skipped by the test, because the two alternatives both mislead: a permanent
 * skip cannot be told apart from a regression that started skipping, and failing on the missing property
 * would make red the ordinary outcome of `connectedAndroidTest`. `taptopay/build.gradle.kts` reads
 * `payabli.cloudProjectNumber` and adds a `notAnnotation` filter for this annotation only when it is
 * absent, so the counts stay honest either way.
 *
 * Set it in `~/.gradle/gradle.properties`, beside `gpr.user`.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
annotation class RequiresCloudProject
