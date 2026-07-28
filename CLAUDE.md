# CLAUDE.md

## Commands

- `./gradlew assembleDebug` - Build all modules
- `./gradlew :MODULE:assembleRelease` - Single module (e.g. `:core`, `:taptopay`)
- `./gradlew test` - All unit tests
- `./gradlew :MODULE:testDebugUnitTest` - Single module unit tests
- `./gradlew connectedAndroidTest` - Instrumentation tests (requires device)
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

## Testing

- Unit tests in `src/test`, instrumented in `src/androidTest` (JUnit4, Espresso). Only template tests exist so far.
- There is no shared fixtures module yet (PLA-2192).
- Card-present and attestation paths need a physical device or mocks rather than an emulator.
