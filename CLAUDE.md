# CLAUDE.md

## Commands

- `./gradlew assembleDebug` - Build all modules
- `./gradlew :MODULE:assembleRelease` - Single module (e.g. `:core`, `:taptopay`)
- `./gradlew test` - All unit tests
- `./gradlew :MODULE:testDebugUnitTest` - Single module unit tests
- `./gradlew connectedAndroidTest` - Instrumentation tests (requires device). **The per-PR CI runs none of
  these**; only the nightly workflow does, and it appends
  `-Pandroid.testInstrumentationRunnerArguments.notAnnotation=com.payabli.sdk.core.ManualDeviceTest,com.payabli.sdk.payin.ManualDeviceTest`.
  Both are named because there is one annotation per module; a command naming one leaves the other module's
  manual tier running. See **Testing** for why, and for the command that runs the excluded tier
- `./gradlew ktlintCheck` - Formatting
- `./gradlew ktlintFormat --no-configuration-cache` - Fix formatting (the flag is required)
- `./gradlew lint` - Android Lint
- `./gradlew createDebugUnitTestCoverageReport` - Coverage, `build/reports/coverage/test/debug/report.xml`
- `./gradlew sonar` - Static analysis (needs `SONAR_TOKEN`; add `--dry-run` to check config only)
- `./gradlew publishToMavenLocal` - Exercise the publish convention

**Setup**: three things a fresh clone lacks, in the order they break. `ANDROID_HOME`, or `sdk.dir` in the gitignored `local.properties`. Then `gpr.user` and `gpr.token` in `~/.gradle/gradle.properties` (never in this repo) for the card reader registry that `:taptopay` resolves from; it needs a classic PAT with `read:packages`. Only `:taptopay` needs it, and the build says so if it is missing. Then, for the attestation tests that make a real Play Integrity request, `payabli.cloudProjectNumber` in the same file: a Google Cloud project number with the Play Integrity API enabled, which a maintainer can supply. It is **not a secret** — every app shipping Play Integrity carries its project number in the binary — but it is environment-scoped and it is the shared daily quota target, so it is configured rather than hard-coded; `taptopay/build.gradle.kts` carries the full reasoning. Without it, `PlayIntegrityRealProjectTest` is filtered out of the run and everything else is unaffected.

**Work tracking**: Linear, Platform team, project `Android SDK`, `Android -` title prefix.

## Architecture

Multi-module Kotlin SDK for card-present and card-not-present payment acceptance.

**Core**: `:core` (`com.payabli.sdk.core`), session, token and transport foundation. Depends on nothing first-party; everything else depends on it.
**Capabilities**: `:taptopay` (card-present) · `:payin` (card-not-present)
**Opt-in**: `:telemetry`
**Aggregate**: `:payabli-android` (umbrella AAR: `:core` + `:payin` + `:telemetry`) · `:payabli-bom` (version constraints)
**Infra**: `:example` (sample app, `com.payabli.example.app`; see `example/README.md`), `build-logic` (convention plugins)

**Key Patterns**

- A capability module depends on `:core` and **never** on a sibling capability. The umbrella deliberately omits `:taptopay` so the card reader dependency stays opt-in; do not add it.
- `:payin` ships the Compose payment form and is the only library module with Compose, so the umbrella carries the Compose runtime. Its public composables need `api` dependencies: `implementation` would leave an integrator unable to call them. The form takes its colours, type and shapes from the host's `MaterialTheme` and names none of its own; a literal colour or size anywhere in `payin/ui` is a defect.
- `minSdk` is **per module, not global**: `:taptopay` is **30**, required by the card reader dependency, and an app linking it must also be 30 or higher. Every published module is **23**. Do not raise the card-not-present modules to match card-present. `:example` is **24**, and publishes nothing: its debug build reaches the local token server over cleartext, and a network security config, which is the only way to permit that for two addresses instead of for everything, is ignored below 24.
- **`:taptopay` is 64-bit only, and nothing declares it.** The card reader pulls `magiccube`, whose AAR carries exactly one native library — `jni/arm64-v8a/libmc3.so`, with no 32-bit build. So an APK linking `:taptopay` will not install on an `armeabi-v7a`-only handset: the install fails with `INSTALL_FAILED_NO_MATCHING_ABIS` before any code runs, which names the ABI but not the dependency that caused it. Measured on an SM-A136U1 (`ro.product.cpu.abilist=armeabi-v7a,armeabi`). This is a vendor constraint like the API 30 floor, it is not expressed in any manifest or Gradle setting, and it means such a device cannot run this module's instrumented tests at all — a stated skip on the bench, not a failure to chase.
- Kotlin compiles through AGP's built-in Kotlin support (AGP 9.2.1, Kotlin 2.2.10, Gradle 9.4.1, daemon JVM 21). There is no `org.jetbrains.kotlin.android` plugin and none should be added.
- Platform-native only: `HttpURLConnection`, Keystore and `javax.crypto`, `kotlinx.serialization`, `kotlinx.coroutines`, Compose. No third-party HTTP client, crypto engine, DI framework, reflection-based JSON mapper or logging framework.
- Convention plugins in `build-logic` carry shared config: `payabli.publish` for publishing, `payabli.quality` for formatting and coverage. Prefer extending those over per-module blocks.
- Dependencies come from `gradle/libs.versions.toml` only. Plugin versions used by `build-logic` are declared there too and pinned again in `build-logic/build.gradle.kts`; keep the two in sync.
- ktlint style rules live in `.editorconfig`. `kotlin.code.style=official`; configuration cache is on, so avoid build logic that reads mutable state at execution time.
- Never log a PAN, token, CVV, expiry, cardholder name or account number. Sensitive input uses a zeroizable buffer overwritten after encryption, never an immutable `String`.
- **A `toString()` is a second route out for anything the logger redacts.** A `data class` synthesizes one over every property, and it reaches assertion failures, exception messages and crash reports without passing through the logger. A type holding an instrument value, an account number or an identifier declares its own. `copy()` is the same hole, since it hands out a second reference to a buffer the original still intends to overwrite.
- **`internal` is a public, name-mangled method on the JVM.** A Java consumer can call an internal accessor that the public Kotlin surface does not offer, so an accessor returning sensitive digits needs `@JvmSynthetic` and not only `internal`.
- **A decoder exception quotes what it rejected.** `SerializationException.message` carries an excerpt of the input, so attaching it as the `cause` of a redacted exception puts that excerpt in every crash report that walks the chain. Keep the code and drop the cause.
- **Catch the narrowest type the surrounding layer already catches.** `runCatching` catches `Throwable`, so a linkage error or a programming mistake becomes whatever recoverable state the failure branch returns; there is no `catch` clause for a static analyzer to flag either. Catching `IllegalArgumentException` where `SerializationException` is meant turns an unrelated argument error into a store reset. And a platform failure outside the supertype being caught escapes the taxonomy entirely: `KeyGenerator.generateKey()` raises `ProviderException`, which is not a `GeneralSecurityException`.
- **A lock is keyed by what it protects, not by the object holding it.** A `Mutex` held as a property serializes callers that share that instance, and a factory can hand out several instances over one backing file, so two of them read the old map and each writes its own. Key it by the resolved path. Separately, `get` under a lock followed by `set` under the same lock is two critical sections and another caller runs between them: a read whose value must still hold when the write lands is one critical section, and a delete carries the value it expects so a stale caller cannot remove a newer record.
- **Cancellation must not leave shared state wedged.** `Mutex.withLock` honours an already-cancelled job, so a cancellation arriving while suspended behind the lock throws before the state is committed or the deferred completed, and every later reader awaits a claim with no owner. Commit and complete under `NonCancellable`. Release the claim for anything thrown, not only for `Exception`: an `Error` on a path that only releases for `Exception` leaves the deferred permanently incomplete.
- **A blocking `HttpURLConnection` call is not interrupted by cancellation or `withTimeout`.** Neither `outputStream` nor `readBytes()` observes the coroutine, so a slow-drip response keeps resetting the socket read timeout and outlives the deadline. A test that asserts only the eventual exception after the server finally responds passes without exercising any of this.
- **A public knob that nothing reads is a defect, not a placeholder.** An integrator sets it, nothing changes, and they cannot tell whether the fault is theirs. The same applies to a type whose only callers are tests: the build is green because the tests are the callers, and no host can reach the capability. Ask what call from a host app, against the published artifacts alone, reaches it.
- **A value written by hand in two places drifts.** Prefer one definition the other sites read. Where a mirror is unavoidable, the commit that changes the source changes every mirror, and something fails when they disagree. Match a mirrored enum by name or an explicit map, never by indexing on the raw value, so an unknown value is a handled case instead of a read past the end.
- **Compose:** `@Immutable` on a type holding a caller-supplied `List`, `Set` or `Map` is a promise Compose acts on, and it may skip the update entirely when the caller mutates the collection it passed in. A button disabled through state is not a single-flight guard, because a second tap lands before the recomposition; guard the submit synchronously. A `clickable` row sized to its text is below the 48dp touch target at normal font scale. `AnimatedVisibility` removes its content from the semantics tree while hidden, which takes a result message and its action away from a screen reader. A label that is a sibling of its control is announced as an unlabelled control; put it in the control's semantics.
- **A money-moving request needs an idempotency key that outlives the process.** Rotate it only on a definitive accepted or refused response. A timeout, a decode failure or a cancellation after the request left may mean the charge landed, so reusing the key is what makes the retry safe. A key generated in state that is rebuilt after process death is a new key for the same attempt, and the retry charges again.

## CI

`.github/workflows/ci.yml`, on push and pull request to `main`. Three jobs:

- **build** - the modules that need no credentials. Runs for everyone, forks included.
- **card-present** - `:taptopay`. Skipped on pull requests from forks, which never receive secrets, so a fork sees it skipped rather than failing on an authentication error.
- **sonar** - analysis after `build`, producing the reports it consumes first.

`.github/workflows/nightly.yml`, on schedule and manual dispatch only, never on a pull request and not a
required check. Two jobs, and the split is a security boundary rather than organisation:

- **nightly** - every unit test plus `:core`'s and `:payin`'s instrumented tests on an emulator. Runs the one third-party
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

**Both scripts are covered by `.github/scripts/tests/`, which needs only `python3` and `git`.** `verify.py`
runs 465 checks, driving the collector as a subprocess inside a synthetic git repository, which is what
`git` is for, and the poster in-process against a fake Slack on loopback; `sabotage.py` breaks each claimed
behaviour in turn and confirms a check goes red, rewriting copies in a scratch directory rather than the
files in the tree, so it is safe to interrupt. No third-party Python package is involved.
`.github/workflows/scripts.yml` runs both, and only when `.github/scripts/**` or that workflow itself
changes, so an ordinary pull request pays nothing for it. An edit that moves a sabotage anchor turns that
workflow red until the anchor is re-pointed in the same change; that is the safeguard working, not a flake.
Read the README there before adding a check, because each of the four disciplines it lists exists because
its absence produced a false pass. It is also the cheap place to check a change to either script: a
scheduled run never fires from a feature branch, and while `nightly.yml` can be dispatched at one, that runs
the whole emulator suite and posts to the channel.

**A green nightly posts nothing, and that is safe only because of the liveness switch.** Do not "fix" the
missing green message. Six of seven messages used to say `Nightly green`, which is what teaches people to
stop reading a channel. Silence would be ambiguous on its own, because "green" and "the workflow stopped
firing" look identical, so the scheduled run on the default branch arms a Slack scheduled message about 26
hours out and cancels the one the previous scheduled run armed. If that nightly stops for any reason, nobody
cancels it and Slack posts the alarm on its own clock.

**Only that run owns the alarm, and that restriction is load-bearing.** A manual dispatch or a probe branch
reports as normal and leaves the alarm alone; a non-owner going quiet is the design rather than a broken path.
Letting any run reset it would measure "somebody ran the nightly at some point", which a dead schedule could
satisfy indefinitely through the occasional dispatch, and that is the exact failure the switch exists to catch.
The marker is also scoped per platform, so a sibling platform reporting into the same channel cannot cancel
this one's alarm. The clock has to live outside GitHub: a watcher hosted on the thing it watches dies with it,
which is why this is not a scheduled digest job.

The window is 26 hours rather than 25 for one reason only: scheduled runs here fire 42 to 53 minutes after
the cron, which is GitHub's documented load delay, so 24 plus 2 leaves headroom without hiding a genuinely
missed night. Widen it only against a measurement.

What the switch is *for* is a separate question, and two documented behaviours answer it rather than setting
the window. This repository is public, so "scheduled workflows are automatically disabled when no repository
activity has occurred in 60 days" applies, and GitHub announces nothing when it happens. And queued
scheduled jobs can be dropped outright under load. Neither produces an error for anything to report, which
is why the absence has to be watched from outside.

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

**The per-file culprit only names a commit that landed since the last green nightly.** `git log -1 -- <file>`
answers who touched a file last, which for an untouched file is a commit that has already passed every
nightly since, so the report used to blame week-old work for a test that had merely turned flaky. Where the
suspect range says the files have not changed, the thread reply says so and names nobody, and no author is
looked up. Where the range is unknown or its commit list came back truncated, it falls back to naming the
commit: a partial range cannot show that anything is outside it.

**A comparison that came out empty is not a comparison that could not be made**, and the two answers are
carried separately for that reason. A re-run of the very commit that went green, and a run of a commit older
than the green baseline, both prove that every culprit was already in the tree when the suite last passed, so
both clear everyone; the summary still renders no range for them, because a span from a commit to itself over
a count of zero says nothing. Rewritten history is the unknown case and falls back. Conflating the two is
what the first version of this did, and it put the blame back on precisely the runs that are flakes by
definition.

**Leave token rotation disabled on the Slack app.** The poster sends a static bearer token and implements no
refresh, so enabling rotation would make the stored secret expire on Slack's schedule and the nightly would
start warning and skipping. Nothing would go red, which is what makes it worth writing down: the failure is
a channel that quietly stops reporting. Enabling rotation means teaching the poster to refresh first.

## Testing

**No test mints its own token, and a token that reaches a real service comes from the local token server.**
A client id and secret belong to `example-server`, which is the only thing in the repository that holds one,
and nothing on a device or in a test process ever does. Three tiers reach a real service and all three obey
it:
`PayInLiveFlowsInstrumentedTest` posts to the server's `/payabli/exchange-token`, `:taptopay`'s
`LocalTokenServer` gets `/payabli/access-token`, and `:example`'s `SampleWalkthroughTest` fetches nothing
itself and points the sample app at the server so the production path does it.

`LiveTestSettings` is what keeps this true by construction: it can forward `environment`, `entryPoint` and
`tokenHost` and nothing else, so a credential has no route into a test. `verify.py` enforces the CI half,
refusing a workflow that hands a client credential to any step but the one running `server.mjs`, and
`sabotage.py` proves those checks fail when someone does. What neither of them catches is a credential
written into a Kotlin test by hand, which is why the rule is here.

Read both halves as written. *Mints*: `ActivationCodeMinter` calls the real service with a bearer it is
**given**, which is the boundary working rather than an exception to it. And *reaches a real service*: a unit
test holding `accessToken = "initial-token"` is asserting on a value, not authenticating with one, and there
are eighteen such literals. Widening this to every token any test holds would describe a rule the suite does
not follow and never should.

**Setup and teardown must not swallow their own failures.** A cleanup that catches and continues lets the
suite run against state the previous run left behind, and the result is a green suite that proves nothing
about a fresh device. Let cleanup failures fail the test.

**A test is known to test something once it has been seen red.** Break the behaviour it names, watch it
fail, revert. The shapes that pass without their subject: an assertion that only checks the line
executed; a hand-maintained list asserted against itself, where adding an entry to neither side leaves
the test green; an assertion that exercises only the success path when the guarantee lives in `finally`,
which needs an injected throw; and an assertion that passes on the emulator's default answer and so never
reaches the branch under test.

- Unit tests in `src/test`, instrumented in `src/androidTest` (JUnit4, Espresso). `:core`'s network,
  error-mapping and logging layers are covered; the other modules are still template-only.
- `LoopbackServer` is a real HTTP server on `java.net.ServerSocket`, so transport tests exercise
  `HttpURLConnection` itself rather than a stub. It uses no `com.sun.*` package, so it runs in both test
  source sets.
- **`src/sharedTest/java` is compiled into both `test` and `androidTest`**, wired in `core/build.gradle.kts`,
  and holds `LoopbackServer` with the fixtures it needs (`TestAuth`, `RecordingLogSink`). Add to `kotlin`
  srcDirs, not `java`: AGP's built-in Kotlin keeps its own source directories and `java` alone leaves `.kt`
  files out of the compilation. This is not a stand-in for a fixtures module and cannot become one: `LogSink`
  and `DefaultSdkLogger` are `internal`, and only compilations of `:core` itself see those. Moving these
  fixtures to a sibling module or to AGP `testFixtures` would mean widening a published security SDK's API to
  suit a test layout. Do not re-propose it. A separate cross-module fixtures module is a
  different thing and still does not exist.
- Two transport behaviours cannot be shown on the JVM, and both are now covered on-device in
  `PayabliServiceInstrumentedTest`: transparent gzip and `PATCH` acceptance. **"Cancellation unblocking a
  blocked read" used to be listed here and was wrong.** Measured, `disconnect()` from another thread unblocks
  a parked `HttpURLConnection` read on this JVM within about two milliseconds, and the whole-call timeout
  tests assert exactly that. The entry was not describing a platform limit; it was shielding a defect from
  scrutiny, since the teardown had never fired at all. Before adding a behaviour to this list, measure it.
- **A wall-clock assertion on an emulator is asserted over attempts, never once.** The call-budget claim is
  the only one here that can only be shown by elapsed time, and on the nightly's emulator that measurement
  is not reliable: under CPU load the same working cut-off landed at 591ms, 1195ms and 1230ms across 40 runs,
  and once turned a nightly red at 1115ms against a 500ms bound. In 6 more of those runs the deadline expired
  before the request reached the server, which is not a cut-off out of a stall either. So
  `assertTheCallBudgetCutsTheCallOutOfTheStall` offers the cut-off three attempts, passes on the first that
  lands, discards an attempt that never put a call in flight, and reports every number plus an unbudgeted
  control when none of them does. A deadline that has stopped firing is late on all three, which is what the
  count is chosen to keep red. Do not answer a timing flake by widening the bound: a bound loose enough for a
  starved machine is loose enough to pass a broken deadline, which is how this test was once green while the
  teardown had never fired.
- **A `platform` package is the instrumented tier, and the boundary is structural rather than a list.** A file
  belongs there when it calls an Android API with no JVM implementation — Keystore, `android.util.*`, a
  `Context`. Nothing else does: "hard to test" is not a reason to move a file, and moving one to quiet a
  coverage number is how the boundary stops meaning anything. `sonar.coverage.exclusions` is `**/platform/**`,
  coverage only, so those files still get issue detection; their tests live in the mirroring package under
  `src/androidTest`. This exists because Sonar gates **introduced** code: `:core`'s module total read 81.5%
  line while the same commit measured 41% on its new lines, because `KeystoreValueCipher` was 121 of 261
  measured units and unreachable from any unit test. Read both numbers, not one.
- **Three test tiers, and the third is excluded from CI rather than skipped.** JVM unit tests; instrumented
  tests the nightly runs on an emulator; and `@ManualDeviceTest`, which that job cannot answer. The bar is what
  the nightly provisions, not what an emulator can do in principle, so the tier holds several reasons rather
  than one. Across the two modules that job runs: two of `:core`'s need a secure element, which an emulator's
  software-backed Keystore fails outright; a third needs a throttled link the job does not start, and passes
  on an emulator once it has one; and `:payin`'s needs client credentials no repository holds. Only the
  secure-element pair is permanent, and each test states its own reason at its declaration. `:taptopay` is out
  of the job's reach for a different reason again, and is covered below. The exclusion is
  `notAnnotation`, verified to leave `skipped="0"` in the results XML with the manual tests absent from it
  entirely. An `@Ignore` or an `Assume` would report a standing skip in Slack every night, and a permanent
  skip cannot be told apart from a regression that started skipping.

  **There is one of that annotation per module, and a command has to name every module it covers.** An
  `androidTest` source set is invisible to another module's, so each declares its own:
  `com.payabli.sdk.core.ManualDeviceTest`, `com.payabli.sdk.payin.ManualDeviceTest` and
  `com.payabli.sdk.taptopay.ManualDeviceTest`. `notAnnotation` takes a **comma-separated list**, so one
  argument covers several modules, and naming one of them leaves the others' manual tiers running. The
  nightly names `:core`'s and `:payin`'s in a single argument for that reason. Measured twice: the runner
  splits the value on commas, and a nightly command carrying both excluded both, where dropping to one would
  have run `:core`'s hardware tier on the emulator and failed it.

  **In `:taptopay` the annotation is not what enforces the exclusion.** A command-line `notAnnotation`
  overwrites what the Gradle DSL sets, so that module excludes its live tier by `notClass`, by name, in
  `taptopay/build.gradle.kts`. Renaming or moving one of those classes silently re-enables it. The live tier
  additionally needs a paypoint and a reachable token server, and is filtered out entirely without them.

  The two rules are not in tension: a command-line value replaces the DSL's for **the same key**, which is
  why `:taptopay` cannot rely on the annotation, while different keys merge, which is why the nightly's
  `notAnnotation` leaves `:payin`'s `notClass` exclusions standing.

  ```bash
  # Only the manual tier, against a wired phone. ANDROID_SERIAL matters when an emulator is also attached.
  ANDROID_SERIAL=<serial> ./gradlew :core:connectedAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.annotation=com.payabli.sdk.core.ManualDeviceTest
  ```

  Put a test there only when the nightly cannot answer the question, which is a bar about what that job
  provisions rather than what an emulator can do. Two of `:core`'s assert the storage and device keys are in
  secure hardware at the device's best level, which on an emulator fails with `SECURITY_LEVEL_SOFTWARE`:
  excluding those is load-bearing, not housekeeping. The third, the slow-link check, passes on an emulator and
  is parked only because the job starts no throttled broker, so it says at its own declaration that it is on
  borrowed time. A test parked for a provisioning gap has to, or the tier quietly becomes a place tests go to
  stop running.

  **A test that needs credentials is gated twice, and the second gate is the one that keeps the counts honest.**
  `PayInLiveFlowsInstrumentedTest` sends real requests, so `payin/build.gradle.kts` excludes it **by name**
  unless three `payabli.liveTest.*` Gradle properties are set, and the annotation marks the tier. **None of
  the three is a credential**: they are `environment`, `entryPoint` and `tokenHost`, and the client secret
  belongs to the token server the test reaches rather than to the device. Nothing about an environment is
  committed. Follow that pattern for anything else in this position: a property, a named exclusion when it is
  absent, and no skip.

  ```bash
  ANDROID_SERIAL=<serial> ./gradlew :payin:connectedDebugAndroidTest \
    -Ppayabli.liveTest.environment=<name> -Ppayabli.liveTest.entryPoint=<entry> \
    -Ppayabli.liveTest.tokenHost=<host:port> \
    -Pandroid.testInstrumentationRunnerArguments.annotation=com.payabli.sdk.payin.ManualDeviceTest
  ```

  One environment per invocation, because the SDK installs one session per process and the environment is one of
  the values it compares, so naming a second one is refused. A fresh token is not: a token is a credential rather
  than an identity and is not compared at all. The tier covers
  what the public flow reaches; charging an already-stored method is not in it, because `PayInFormInstrument`
  builds only `Card` and `BankAccount` from a form.
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
  request red. A manual and nightly emulator job is planned, and it is not a required per-PR check.
- Card-present paths need a physical device or mocks rather than an emulator.
- **Attestation runs on an emulator and has no manual tier, both of which were measured.** Attestation lives
  in `:taptopay`, not `:core`: the platform verdict gates arming the card reader, and keeping it there is
  what keeps Play services off the umbrella AAR. Against a real cloud project with the API enabled, both
  request shapes returned **a token** on a `google_apis_playstore` emulator (API 37) and on three phones
  from two manufacturers, spanning API 33 to 36, all debug-signed and adb-installed.
  `UNRECOGNIZED_VERSION` and `UNLICENSED` are verdict values inside the token, not reasons the call fails.
  Hardware and a Play-Store emulator agreed at every client-observable level, so a manual tier can ask
  nothing the emulator tier cannot, and there is none. Reading verdict *contents* needs a server-side decode
  through the same cloud project, which is separate work with no owner.
- **The attestation instrumented tier does not run in the nightly, and the blocker is a credential, not
  hardware.** Building `:taptopay` resolves the card reader from the Fiserv GitHub Packages repo, so running
  its instrumented tests in the nightly would hand `GPR_TOKEN` to the third-party emulator action, which is
  the exposure the job split exists to prevent. It waits on `:taptopay` getting its own instrumented job.
