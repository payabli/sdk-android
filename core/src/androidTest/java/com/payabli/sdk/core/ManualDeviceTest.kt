package com.payabli.sdk.core

/**
 * Marks a test that needs a physical device and is therefore **excluded from CI**, not skipped there.
 *
 * Excluded rather than skipped on purpose. The nightly reporter derives passed from total minus failed minus
 * skipped and prints the skip count, so a device-only test parked with `@Ignore` or an `Assume` would report
 * a skip every night, and a standing skip is indistinguishable from a regression that started skipping.
 * `AndroidJUnitRunner`'s annotation filtering omits these from the run entirely, so the counts stay honest.
 *
 * ```
 * # CI, and the nightly: everything except these
 * ./gradlew :core:connectedAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.notAnnotation=com.payabli.sdk.core.ManualDeviceTest
 *
 * # Local, wired phone: only these
 * ANDROID_SERIAL=<serial> ./gradlew :core:connectedAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.annotation=com.payabli.sdk.core.ManualDeviceTest
 * ```
 *
 * Use it only where an emulator cannot answer the question. A test that would pass on an emulator belongs in
 * the ordinary instrumented suite, where CI actually runs it.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
annotation class ManualDeviceTest
