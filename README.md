# Payabli Android SDK

Accept payments in an Android app: a Compose payment form for card and bank, reusable payment methods,
and contactless card-present acceptance on supported hardware.

> **Not published yet.** The coordinates below are what this SDK will release under, and nothing resolves
> them today. The publishing machinery is in place and the Maven Central namespace is not yet claimed, so
> treat this page as the shape of the integration rather than something to paste into a build that has to
> work this afternoon. Build from source or `./gradlew publishToMavenLocal` in the meantime.

## Requirements

| | |
|---|---|
| `minSdk` | **23** for everything except card-present |
| `minSdk`, card-present | **30**, required by the card reader, and an app linking it must be 30 or higher too |
| Card-present ABI | **`arm64-v8a` only.** The reader ships no 32-bit build, so an APK linking it will not install on an `armeabi-v7a`-only handset |
| Java | 11 bytecode |

## Adding it

Declare the BOM once and leave versions off everything else. It manages versions only, so depending on it
pulls no code.

```kotlin
dependencies {
    implementation(platform("com.payabli:sdk-android-bom:<version>"))
    implementation("com.payabli:sdk-android")
}
```

Nothing else to declare: the artifacts are on Maven Central, which is already in almost every build.

### What each artifact is

| Artifact | What it gives you |
|---|---|
| `sdk-android` | The umbrella. Session, the pay-in form, telemetry. **Start here.** |
| `sdk-android-core` | Session, tokens and transport on their own |
| `sdk-android-payin` | The Compose payment form and the pay-in operations |
| `sdk-android-telemetry` | Opt-in telemetry |
| `sdk-android-taptopay` | Card-present. **Deliberately not in the umbrella**, so an app that never takes a contactless payment links no card reader and no Play services |
| `sdk-android-bom` | The version constraints above |

### Card-present, which needs one more repository

The card reader is a Fiserv component distributed by Payabli, not an open-source artifact, so it is not on
Maven Central. Scope the repository to it rather than adding it globally, so nothing else in your build can
resolve from there:

```kotlin
maven {
    url = uri("https://sdk.payabli.com/maven")
    content { includeGroup("com.fiserv.ch") }
}
```

Then add `implementation("com.payabli:sdk-android-taptopay")`.

**The reader's version is strict.** If something else in your graph pulls a newer one, the build fails
naming the conflict rather than quietly running a version card-present was never certified against.

If you are not taking contactless payments, ignore this section entirely.

## Using it

Three calls: a session, a flow, and the form. They are written out, numbered and compiled in
[`example/src/main/java/com/payabli/example/app/demo/simple/SimpleCaptureScreen.kt`](example/src/main/java/com/payabli/example/app/demo/simple/SimpleCaptureScreen.kt),
and [`example/README.md`](example/README.md) walks through them.

Not reproduced here, for the reason that file's own README gives: a fenced block is not compiled, so a
signature change would leave this page describing an integration that no longer builds.

Two things that catch people out and are worth knowing before you start:

- **The access token comes from your backend, never from the app.** The SDK is configured with a token
  your server mints; a client id and secret do not belong in a mobile binary. The sample ships a token
  server for local work.
- **A retry needs the key from the first attempt.** After an outcome you did not see — a timeout, a lost
  connection — sending `retryKey` is what settles the original charge instead of making a second one.

## Environments

The SDK carries `SANDBOX` and `PRODUCTION`. A build can add others through
`payabli.sdk.extraEnvironments`, which is for internal use: publishing is refused while it is set, so no
released artifact can carry one.

## Licence

The SDK is distributed under the PayabliSDK Commercial License. [`LICENSE`](LICENSE) is the text, and a
copy travels inside every published artifact under `META-INF/`.

Third-party components are enumerated in [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md), and each
published module carries a CycloneDX SBOM.

## Contributing

[`CLAUDE.md`](CLAUDE.md) is the working reference for this repository: the commands, the module layout,
and the constraints that are not obvious from the code.
