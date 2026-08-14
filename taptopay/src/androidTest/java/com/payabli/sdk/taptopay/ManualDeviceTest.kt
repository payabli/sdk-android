package com.payabli.sdk.taptopay

/**
 * Marks a test that needs an environment CI does not provide, so it is **excluded** there, not skipped.
 *
 * A duplicate of `:core`'s: an androidTest source set is not published, so this module cannot see that one.
 *
 * **This annotation does not enforce the exclusion here.** A command-line `notAnnotation` argument
 * overwrites what the Gradle DSL sets, and the nightly and manual tier both pass one, so the build file
 * excludes by `notClass` instead. This marks the tier for a reader and for a command line that filters on it.
 *
 * Excluded, not `@Ignore`d: the nightly derives its pass count by subtracting skips, so a standing
 * skip is indistinguishable from a regression.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
annotation class ManualDeviceTest
