package com.payabli.sdk.payin

/**
 * Marks a test excluded from CI rather than skipped there, as `com.payabli.sdk.core.ManualDeviceTest` does.
 *
 * A second annotation because an `androidTest` source set is not visible to another module's, so `:core`'s
 * cannot be referenced here. Any command that excludes one has to name both:
 *
 * ```
 * ./gradlew :payin:connectedAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.notAnnotation=com.payabli.sdk.payin.ManualDeviceTest
 *
 * # Only this tier, against a wired phone
 * ANDROID_SERIAL=<serial> ./gradlew :payin:connectedAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.annotation=com.payabli.sdk.payin.ManualDeviceTest
 * ```
 *
 * **The one test here is parked for a provisioning reason, which `:core`'s annotation calls out as the weaker
 * one, so it says so and names the work.** `PayInLiveFlowsInstrumentedTest` sends real payments to a real
 * environment: it needs client credentials and would answer nothing useful without them. Nothing about it
 * requires a phone — an emulator with a network runs it — so it moves into an unattended job the moment one can
 * hold the credentials as secrets, which is the same job `:payin`'s ordinary instrumented tests are waiting for.
 * Until then it is excluded twice over: by this annotation, and by name from `payin/build.gradle.kts` whenever
 * the credentials are absent, so a run without them reports no skip rather than a standing one.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
annotation class ManualDeviceTest
