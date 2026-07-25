# CLAUDE.md

## Commands

- `./gradlew assembleDebug` - Build all modules
- `./gradlew :MODULE:assembleRelease` - Single module (e.g. `:core`, `:taptopay`)
- `./gradlew test` - All unit tests
- `./gradlew :MODULE:testDebugUnitTest` - Single module unit tests
- `./gradlew connectedAndroidTest` - Instrumentation tests (requires device)
- No static analysis, formatter or CI is configured yet (PLA-2170)

**Setup**: two things a fresh clone lacks, in the order they break: `ANDROID_HOME` (or `sdk.dir` in the gitignored `local.properties`), then `gpr.user`/`gpr.token` in `~/.gradle/gradle.properties` (or `GITHUB_ACTOR`/`GITHUB_TOKEN`, scope `read:packages`) for the Fiserv GitHub Packages repo `:taptopay` resolves from.

**Work tracking**: Linear, Platform team, project `Android SDK`, `Android -` title prefix. Design authority and the cross-platform parity rules live in `../mobile_sdk_agent/CLAUDE.md`; read it before changing anything on the public surface.

## Architecture

Multi-module Kotlin SDK for card-present and card-not-present payment acceptance.

**Core**: `:core` (`com.payabli.sdk.core`), session, token and transport foundation. Depends on nothing first-party; everything else depends on it.
**Capabilities**: `:taptopay` (card-present, pulls `com.fiserv.ch:ttp-payment`) · `:payin` (card-not-present)
**Opt-in**: `:telemetry`
**Aggregate**: `:payabli-android` (umbrella AAR: `:core` + `:payin` + `:telemetry`) · `:payabli-bom` (version constraints)
**Infra**: `:example` (sample app), `build-logic` (convention plugins)

**Key Patterns**

- A capability module depends on `:core` and **never** on a sibling capability (SEC-001 Section 5). The umbrella deliberately omits `:taptopay` so the Fiserv dependency stays opt-in; do not add it.
- `minSdk` is **30** (SEC-001 Section 9.6). The modules currently declare 19, which is a defect to fix when touching them. Card-present gates additionally on Android 12+ and fails closed rather than degrading to software keys.
- Kotlin compiles through AGP's built-in Kotlin support (AGP 9.2.1, Kotlin 2.2.10, Gradle 9.4.1, daemon JVM 21). There is no `org.jetbrains.kotlin.android` plugin and none should be added.
- Platform-native only: `HttpURLConnection`, Keystore + `javax.crypto`, `kotlinx.serialization`, `kotlinx.coroutines`, Compose. No third-party HTTP client, crypto engine, DI framework, reflection-based JSON mapper or logging framework. The single permitted exception is a reviewed JOSE library for the instrument-data JWE, isolated behind an interface.
- The device key is EC P-256 in StrongBox with TEE fallback, generated separately from attestation, non-exportable and used by handle. Attestation is Play Integrity.
- Dependencies come from `gradle/libs.versions.toml` only. `kotlin.code.style=official`; configuration cache is on, so no build logic that reads mutable state at execution time.
- Never log a PAN, token, CVV, expiry, cardholder name or account number. Sensitive input uses a zeroizable buffer overwritten after encryption, never an immutable `String`.

## Testing

- Unit tests in `src/test`, instrumented in `src/androidTest` (JUnit4, Espresso). Only template tests exist so far.
- There is no shared fixtures module yet (PLA-2192). The parity target is iOS `PayabliSDKTestUtils`: stub HTTP transport, in-memory secure storage, mock card-reader provider and attestor.
- Card-present and attestation paths need a physical device or mocks; Play Integrity does not return a usable verdict on an emulator.
