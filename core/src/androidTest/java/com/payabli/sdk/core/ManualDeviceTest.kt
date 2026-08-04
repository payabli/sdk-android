package com.payabli.sdk.core

/**
 * Marks a test that needs an environment CI does not provide and is therefore **excluded from CI**, not skipped
 * there.
 *
 * Two kinds so far, and the distinction matters when reading a manual run. A test needing real **hardware**,
 * where an emulator answers the wrong thing no matter how correct the code is. And a test needing a real
 * **emulator configuration**, where a physical device is the thing that cannot answer: a bandwidth profile is
 * something only an emulator can impose, so those tests skip on a phone. Running the tier against everything
 * attached is still right, and some of it will skip either way.
 *
 * Excluded rather than skipped on purpose. The nightly reporter derives passed from total minus failed minus
 * skipped and prints the skip count, so a device-only test parked with `@Ignore` or an `Assume` would report
 * a skip every night, and a standing skip is indistinguishable from a regression that started skipping.
 * `AndroidJUnitRunner`'s annotation filtering omits these from the run entirely, so the counts stay honest.
 *
 * ```
 * # The nightly emulator job: everything except these
 * ./gradlew :core:connectedAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.notAnnotation=com.payabli.sdk.core.ManualDeviceTest
 *
 * # Local, wired phone: only these
 * ANDROID_SERIAL=<serial> ./gradlew :core:connectedAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.annotation=com.payabli.sdk.core.ManualDeviceTest
 * ```
 *
 * Use it only where the **nightly's** emulator cannot answer the question. That job runs a stock image on a
 * default network, so the bar is what it provisions rather than what an emulator can do in principle: a test
 * that would pass there belongs in the ordinary instrumented suite. Needing a secure element and needing a
 * throttled link both clear the bar, and neither can be arranged in that job. **No instrumented test runs per
 * pull request**, either tier, so neither gates a merge.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
annotation class ManualDeviceTest
