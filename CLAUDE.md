# CLAUDE.md

## Commands

- `./gradlew assembleDebug` - Build all modules
- `./gradlew :MODULE:assembleRelease` - Single module (e.g. `:core`, `:taptopay`)
- `./gradlew test` - All unit tests
- `./gradlew :MODULE:testDebugUnitTest` - Single module unit tests
- `./gradlew connectedAndroidTest` - Instrumentation tests (requires device). **The per-PR CI runs none of
  these**; only the nightly workflow does, and it appends
  `-Pandroid.testInstrumentationRunnerArguments.notAnnotation=com.payabli.sdk.core.ManualDeviceTest`. See
  **Testing** for why, and for the command that runs the excluded tier
- `./gradlew ktlintCheck` - Formatting
- `./gradlew ktlintFormat --no-configuration-cache` - Fix formatting (the flag is required)
- `./gradlew lint` - Android Lint
- `./gradlew createDebugUnitTestCoverageReport` - Coverage, `build/reports/coverage/test/debug/report.xml`
- `./gradlew sonar` - Static analysis (needs `SONAR_TOKEN`; add `--dry-run` to check config only)
- `./gradlew publishToMavenLocal` - Exercise the publish convention

**Setup**: two things a fresh clone lacks, in the order they break. `ANDROID_HOME`, or `sdk.dir` in the gitignored `local.properties`. Then `gpr.user` and `gpr.token` in `~/.gradle/gradle.properties` (never in this repo) for the card reader registry that `:taptopay` resolves from; it needs a classic PAT with `read:packages`. Only `:taptopay` needs it, and the build says so if it is missing.

**Work tracking**: Linear, Platform team, project `Android SDK`, `Android -` title prefix.

## Architecture

Multi-module Kotlin SDK for card-present and card-not-present payment acceptance.

**Core**: `:core` (`com.payabli.sdk.core`), session, token and transport foundation. Depends on nothing first-party; everything else depends on it.
**Capabilities**: `:taptopay` (card-present) · `:payin` (card-not-present)
**Opt-in**: `:telemetry`
**Aggregate**: `:payabli-android` (umbrella AAR: `:core` + `:payin` + `:telemetry`) · `:payabli-bom` (version constraints)
**Infra**: `:example` (sample app, `com.payabli.example`), `build-logic` (convention plugins)

**Key Patterns**

- A capability module depends on `:core` and **never** on a sibling capability. The umbrella deliberately omits `:taptopay` so the card reader dependency stays opt-in; do not add it.
- `minSdk` is **per module, not global**: `:taptopay` is **30**, required by the card reader dependency, and an app linking it must also be 30 or higher. Everything else is **23**. Do not raise the card-not-present modules to match card-present.
- Kotlin compiles through AGP's built-in Kotlin support (AGP 9.2.1, Kotlin 2.2.10, Gradle 9.4.1, daemon JVM 21). There is no `org.jetbrains.kotlin.android` plugin and none should be added.
- Platform-native only: `HttpURLConnection`, Keystore and `javax.crypto`, `kotlinx.serialization`, `kotlinx.coroutines`, Compose. No third-party HTTP client, crypto engine, DI framework, reflection-based JSON mapper or logging framework.
- Convention plugins in `build-logic` carry shared config: `payabli.publish` for publishing, `payabli.quality` for formatting and coverage. Prefer extending those over per-module blocks.
- Dependencies come from `gradle/libs.versions.toml` only. Plugin versions used by `build-logic` are declared there too and pinned again in `build-logic/build.gradle.kts`; keep the two in sync.
- ktlint style rules live in `.editorconfig`. `kotlin.code.style=official`; configuration cache is on, so avoid build logic that reads mutable state at execution time.
- Never log a PAN, token, CVV, expiry, cardholder name or account number. Sensitive input uses a zeroizable buffer overwritten after encryption, never an immutable `String`.

## CI

`.github/workflows/ci.yml`, on push and pull request to `main`. Three jobs:

- **build** - the modules that need no credentials. Runs for everyone, forks included.
- **card-present** - `:taptopay`. Skipped on pull requests from forks, which never receive secrets, so a fork sees it skipped rather than failing on an authentication error.
- **sonar** - analysis after `build`, producing the reports it consumes first.

`.github/workflows/nightly.yml`, on schedule and manual dispatch only, never on a pull request and not a
required check. Two jobs, and the split is a security boundary rather than organisation:

- **nightly** - every unit test plus `:core`'s instrumented tests on an emulator. Runs the one third-party
  action in the repository, so no Slack credential exists in it. Ends by deciding the verdict and gating on
  it, so the run result never depends on the reporting job.
- **report** - `needs: nightly`, holds the bot token, runs nothing third-party. Posts a summary to
  `#mobile-sdk-nightly-build` and the failure detail in that message's thread. `if: ${{ !cancelled() }}`
  rather than `always()`, so a test job that timed out is still announced while a run superseded by
  `cancel-in-progress` stays quiet.

The two halves talk through a `nightly-facts` artifact: `.github/scripts/nightly_report.py` parses the
JUnit XML and writes the facts, the verdict and the stack traces (to the job summary, which is what
Slack links; a trace over 4000 characters is trimmed in the middle and the unabridged JUnit XML stays in the
`nightly-reports` artifact); `.github/scripts/nightly_slack.py` renders and posts them. Parsing has to stay in the test
job because the build outputs and the git history the culprit lookup needs are both there, and the verdict
has to be decided there because that is where the gate reads it.

**A green nightly posts nothing, and that is safe only because of the liveness switch.** Do not "fix" the
missing green message. Six of seven messages used to say `Nightly green`, which is what teaches people to
stop reading a channel. Silence would be ambiguous on its own, because "green" and "the workflow stopped
firing" look identical, so every run arms a Slack scheduled message about 26 hours out and cancels the one
the previous run armed. If the nightly stops for any reason, nobody cancels it and Slack posts the alarm on
its own clock. The clock has to live outside GitHub: a watcher hosted on the thing it watches dies with it,
which is why this is not a scheduled digest job.

Three reasons the window is 26 hours rather than 25: scheduled runs here fire 42 to 53 minutes after the
cron, which is GitHub's documented load delay; this repository is public, so "scheduled workflows are
automatically disabled when no repository activity has occurred in 60 days" applies and GitHub announces
nothing when it happens; and queued scheduled jobs can be dropped outright under load. None of those
produces an error to report, which is the whole reason the switch exists.

The reset runs through the same Slack API as the report, deliberately. If Slack is unreachable the reset
fails too, the switch stays armed and it fires, which is correct: the switch asserts that the channel heard
from the nightly, and if nothing could reach the channel then it did not.

Configuration, all optional, and every one of them absent means warn and skip rather than fail:
`SLACK_BOT_TOKEN` (secret, needs `chat:write`), `SLACK_CHANNEL_ID` (variable, not a secret), and
`SLACK_MENTION_CULPRITS` (variable). The last turns the probable-culprit author into an `@`-mention and
additionally needs `users:read.email`; it is off by default because the culprit is a labelled heuristic
that has been wrong before, and pinging its author at 3am is a team-norm decision. **Enabling it also widens
the blast radius of a tampered facts artifact**, which is written in the job that runs the third-party
emulator action: the author email is looked up as given, so a compromised action could make the bot ping
anyone. Off, nothing is looked up and no mention is emitted. Weigh that alongside the norm, not after it.

**Leave token rotation disabled on the Slack app.** The poster sends a static bearer token and implements no
refresh, so enabling rotation would make the stored secret expire on Slack's schedule and the nightly would
start warning and skipping. Nothing would go red, which is what makes it worth writing down: the failure is
a channel that quietly stops reporting. Enabling rotation means teaching the poster to refresh first.

## Testing

- Unit tests in `src/test`, instrumented in `src/androidTest` (JUnit4, Espresso). `:core`'s network,
  error-mapping and logging layers are covered; the other modules are still template-only.
- `LoopbackServer` is a real HTTP server on `java.net.ServerSocket`, so transport tests exercise
  `HttpURLConnection` itself rather than a stub. It uses no `com.sun.*` package, so it runs in both test
  source sets.
- **`src/sharedTest/java` is compiled into both `test` and `androidTest`**, wired in `core/build.gradle.kts`,
  and holds `LoopbackServer` with the fixtures it needs (`TestAuth`, `RecordingLogSink`). Add to `kotlin`
  srcDirs, not `java`: AGP's built-in Kotlin keeps its own source directories and `java` alone leaves `.kt`
  files out of the compilation. This is not a stand-in for a fixtures module and cannot become one: `LogSink`
  and `DefaultPayabliLogger` are `internal`, and only compilations of `:core` itself see those. Moving these
  fixtures to a sibling module or to AGP `testFixtures` would mean widening a published security SDK's API to
  suit a test layout. Do not re-propose it. The separate cross-module fixtures module (PLA-2192) is a
  different thing and still does not exist.
- Two transport behaviours cannot be shown on the JVM, and both are now covered on-device in
  `PayabliServiceInstrumentedTest`: transparent gzip and `PATCH` acceptance. **"Cancellation unblocking a
  blocked read" used to be listed here and was wrong.** Measured, `disconnect()` from another thread unblocks
  a parked `HttpURLConnection` read on this JVM within about two milliseconds, and the whole-call timeout
  tests assert exactly that. The entry was not describing a platform limit; it was shielding a defect from
  scrutiny, since the teardown had never fired at all. Before adding a behaviour to this list, measure it.
- **A `platform` package is the instrumented tier, and the boundary is structural rather than a list.** A file
  belongs there when it calls an Android API with no JVM implementation — Keystore, `android.util.*`, a
  `Context`. Nothing else does: "hard to test" is not a reason to move a file, and moving one to quiet a
  coverage number is how the boundary stops meaning anything. `sonar.coverage.exclusions` is `**/platform/**`,
  coverage only, so those files still get issue detection; their tests live in the mirroring package under
  `src/androidTest`. This exists because Sonar gates **introduced** code: `:core`'s module total read 81.5%
  line while the same commit measured 41% on its new lines, because `KeystoreValueCipher` was 121 of 261
  measured units and unreachable from any unit test. Read both numbers, not one.
- **Three test tiers, and the third is excluded from CI rather than skipped.** JVM unit tests; instrumented
  tests the nightly runs on an emulator; and `@ManualDeviceTest`, which needs real hardware. The exclusion is
  `notAnnotation`, verified to leave `skipped="0"` in the results XML with the manual tests absent from it
  entirely. An `@Ignore` or an `Assume` would report a standing skip in Slack every night, and a permanent
  skip cannot be told apart from a regression that started skipping.

  ```bash
  # Only the manual tier, against a wired phone. ANDROID_SERIAL matters when an emulator is also attached.
  ANDROID_SERIAL=<serial> ./gradlew :core:connectedAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.annotation=com.payabli.sdk.core.ManualDeviceTest
  ```

  Put a test there only when an emulator cannot answer the question. The current ones assert the storage key
  is in secure hardware at the device's best level, which on an emulator fails with `SECURITY_LEVEL_SOFTWARE`:
  excluding them is load-bearing, not housekeeping.
- **`KeyPermanentlyInvalidatedException` is handled defensively, not reachably, and there is no manual
  procedure for it.** An earlier version of this file described one: write a value, change a credential, read
  it back. That cannot work. The storage key deliberately omits `setUserAuthenticationRequired`, because the
  refresh secret is read during background refresh with nobody present, so enrollment and lockscreen changes
  do not invalidate it. The manual tier also deletes key and file in `tearDown`, so nothing would survive the
  credential change anyway. What **is** covered, on an emulator, is both reachable lost-key outcomes: a
  deleted alias reports `KeyInvalidated` and clears the store, a replaced alias reports `ValueUnreadable` for
  the entry read. Do not reintroduce the credential-change instruction.
- Two traps in the instrumented setup, both of which fail in a way that does not name its cause.
  `androidx.test.ext:junit` does **not** bring `androidx.test:runner`, so without it the test APK installs and
  dies with `ClassNotFoundException` on `AndroidJUnitRunner` before any test runs. And the harness pins
  `127.0.0.1` rather than `InetAddress.getLoopbackAddress()`, which answers `::1` on an emulator: an
  unbracketed IPv6 literal is not a URL authority, so the base URL parses to no host and every instrumented
  test fails as invalid configuration while every unit test still passes.
- **CI runs no instrumented test.** All jobs are `ubuntu-latest` with no emulator, so `connectedAndroidTest`
  is a deliberate local step and a regression in the three device-only behaviours will not turn a pull
  request red. PLA-2306 adds a manual and nightly emulator job, deliberately not a required per-PR check.
- Card-present and attestation paths need a physical device or mocks rather than an emulator.
