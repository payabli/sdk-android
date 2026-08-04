# Third-Party Notices

The Payabli Android SDK is distributed under the **PayabliSDK Commercial License**
(see `LICENSE`). It incorporates or depends on the third-party components listed
below. This file satisfies the attribution requirements of those components; it
does **not** grant any rights to the PayabliSDK itself.

A complete, machine-readable inventory of every component and its license — including
all transitive dependencies, per module — is generated as a CycloneDX SBOM at
`<module>/build/reports/cyclonedx/bom.json` (task `cyclonedxBom`).

---

## Open-source components

The following are pulled in as declared dependencies and are licensed under the
**Apache License 2.0** (https://www.apache.org/licenses/LICENSE-2.0) unless noted.
Full transitive set: see each module's CycloneDX SBOM.

| Component | Coordinates | License |
|---|---|---|
| Kotlin Standard Library | `org.jetbrains.kotlin:kotlin-stdlib` | Apache-2.0 |
| AndroidX AppCompat | `androidx.appcompat:appcompat` | Apache-2.0 |
| AndroidX Core KTX | `androidx.core:core-ktx` | Apache-2.0 |
| Material Components for Android | `com.google.android.material:material` | Apache-2.0 |
| AndroidX (transitive: activity, annotation, lifecycle, …) | `androidx.*` | Apache-2.0 |
| Play Integrity API | `com.google.android.play:integrity` | Apache-2.0 |
| Play Core Common (transitive) | `com.google.android.play:core-common` | Apache-2.0 |
| Google Play services (transitive: basement, tasks) | `com.google.android.gms:play-services-*` | Apache-2.0 |
| Kotlin coroutines Play services adapter | `org.jetbrains.kotlinx:kotlinx-coroutines-play-services` | Apache-2.0 |

> Apache-2.0 NOTICE propagation: where an upstream component ships its own `NOTICE`
> file, that notice is preserved in the component's own artifact and enumerated in
> the per-module SBOM. Consumers performing app-level license aggregation (e.g.
> `play-services-oss-licenses`) will surface these automatically.

---

## Proprietary components

These are commercial components used under license. They are **not** open source
and are governed by their vendors' own agreements, not the Apache/MIT terms above.

### Fiserv CommerceHub Tap to Pay SDK

- **Coordinates:** `com.fiserv.ch:ttp-payment`
- **Scope:** Bundled/depended on **only** by the `payabli-taptopay` (card-present /
  Tap to Pay) module. The `payabli-core`, `payabli-payin`, `payabli-telemetry`
  modules and the `sdk-android` umbrella artifact do **not** include it.
- **Copyright:** © Fiserv, Inc. All rights reserved. Used under license.

> ⚠️ **Fill in from your Fiserv agreement.** The Fiserv artifact ships no license
> file and its POM declares no `<licenses>`, so the exact required attribution /
> terms text must be taken from the Fiserv CommerceHub developer agreement and
> pasted here (and kept in sync with the version you ship). Replace this block with
> the verbatim notice Fiserv requires.

<!-- BEGIN FISERV LICENSE TEXT (verbatim, from Fiserv agreement) -->

<!-- END FISERV LICENSE TEXT -->

---

_Last updated: keep this file in sync when dependencies or their versions change._
