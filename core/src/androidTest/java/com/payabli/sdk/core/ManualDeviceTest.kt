package com.payabli.sdk.core

/**
 * Marks a test that needs an environment CI does not provide and is therefore **excluded from CI**, not skipped
 * there.
 *
 * **The qualifying reason is that the test would fail or answer wrongly in the nightly, not that the nightly
 * has not been taught to run it.** The storage tests qualify on those terms: an emulator's Keystore is
 * software-backed, so a hardware assertion fails there no matter how correct the code is. "The nightly does
 * not set this up yet" is a provisioning gap and a different thing, because it is fixed by changing the
 * workflow rather than by owning a phone. A test parked here for that reason is on borrowed time and says so
 * at its own declaration, with the work to move it recorded; it does not get to sit here quietly, because a
 * test that never runs unattended cannot catch a regression.
 *
 * Some tests here are also emulator-only rather than phone-only, so running the tier against everything
 * attached is still right and part of it will skip either way. Read a manual run's skips with that in mind.
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
