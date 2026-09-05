# Reviewing this repository

A multi-module Kotlin SDK for accepting card-present and card-not-present payments, plus a sample app that
demonstrates it.

## What each module is

| Module | What it is | Published |
|---|---|---|
| `:core` | Session, token and transport foundation. Depends on nothing first-party. | yes |
| `:payin` | Card-not-present: the clients, the submission holder and the Compose payment form. | yes |
| `:taptopay` | Card-present: attestation and the card reader. | **withheld** |
| `:telemetry` | Opt-in instrumentation. | yes |
| `:payabli-android` | Umbrella AAR over `:core`, `:payin` and `:telemetry`. | yes |
| `:payabli-bom` | Version constraints. | yes |
| `:example` | The sample app. Not shipped, not depended on by anything. | **no** |
| `build-logic` | Convention plugins. | no |

## `:example` is sample code, and its job is to be read

Everything under `example/` exists to show an integrator how to call the SDK and to give us something to run
the SDK against on a real device. It is not a product, it has no users, and no one depends on it.

**Held exactly as high as the SDK:** correctness, and anything touching payment data. A sample is copied, so
a mistake in it propagates. Logging a PAN, keeping a card number in a `String`, mishandling an idempotency
key, or a state machine that can strand a payment are all real findings here, and several such have been
found and fixed.

**Not what it is for:** breadth of abstraction, extensibility, configurability, production hardening for
conditions a demo cannot meet, or defences against inputs only its own code produces. A demo screen that
does the one thing it demonstrates, directly, is finished. Suggesting it grow an interface for a second
implementation that will never exist makes it worse at its job.

**Already treated as a sample by the build**, and worth knowing before raising something the project has
already decided: the sample app's `ui` package is exempt from Sonar coverage and from the `kotlin:S107`
long-parameter-list rule. Both are set in the root `build.gradle.kts`, under `sonar.coverage.exclusions` and
the `demoComposables` criteria. Composables need a composition and therefore a device, and demo composables
take long parameter lists on purpose.

## Conventions that are deliberate

These are settled decisions. A finding that amounts to reversing one is not a finding.

- **Platform-native only.** `HttpURLConnection`, Keystore and `javax.crypto`, `kotlinx.serialization`,
  `kotlinx.coroutines`, Compose. No third-party HTTP client, crypto engine, DI framework, reflection-based
  JSON mapper or logging framework. Proposals to adopt one are out of scope by policy, not by oversight.
- **A capability module never depends on a sibling capability**, and the umbrella deliberately omits
  `:taptopay` so the card reader dependency stays opt-in.
- **`minSdk` is per module.** `:taptopay` is 30 because its card reader dependency requires it; published
  modules are 23; `:example` is 30, taking that floor because it links `:taptopay`. Aligning the published
  modules to it is not an improvement.
- **The payment form names no colour or measurement of its own**, taking them from the host's
  `MaterialTheme`. A literal colour or size under `payin/.../ui` is a defect; the absence of one is not.
- **Sensitive input lives in a zeroizable buffer**, never an immutable `String`, and is overwritten after
  use. Nothing logs a PAN, token, CVV, expiry, cardholder name or account number, at any level.
- **Comments state what is hard to change safely** — a security boundary, a wire contract, a concurrency
  rule, a platform quirk that fails without naming its cause — and the line that says what a declaration is.
  Most code carries none, deliberately. A request for more prose is usually a request for something that
  will drift out of step with the code.

## What is most useful in a review

- A claim in a comment or KDoc that the code under it no longer supports.
- A public API whose stated contract does not hold on some path, especially one that returns null or throws
  where the documentation promises otherwise.
- A state machine that can reach a state nothing recovers from.
- Anything that widens what a published artifact exposes.
- A test that would still pass with the behaviour it names removed.
