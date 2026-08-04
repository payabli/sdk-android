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
| Kotlin coroutines Play services adapter | `org.jetbrains.kotlinx:kotlinx-coroutines-play-services` | Apache-2.0 |

> Apache-2.0 NOTICE propagation: where an upstream component ships its own `NOTICE`
> file, that notice is preserved in the component's own artifact and enumerated in
> the per-module SBOM. Consumers performing app-level license aggregation (e.g.
> `play-services-oss-licenses`) will surface these automatically.

---

## Google Play components, under Google's own terms

**These are not open source, and they are not Apache-2.0.** Each carries a licence declared by Google in
its own published POM, quoted below with the URL that POM gives. They reach `:taptopay` through the Play
Integrity dependency; no other module links them, and the umbrella artifact omits `:taptopay`, so a
card-not-present integrator receives none of them.

| Component | Coordinates | Licence as declared in the artifact's POM |
|---|---|---|
| Play Integrity API | `com.google.android.play:integrity` | Play Integrity API Terms of Service — https://developer.android.com/google/play/integrity/overview#tos |
| Play Core Common (transitive) | `com.google.android.play:core-common` | Play Core Software Development Kit Terms of Service — https://developer.android.com/guide/playcore/license |
| Google Play services basement (transitive) | `com.google.android.gms:play-services-basement` | Android Software Development Kit License — https://developer.android.com/studio/terms.html |
| Google Play services tasks (transitive) | `com.google.android.gms:play-services-tasks` | Android Software Development Kit License — https://developer.android.com/studio/terms.html |

These were briefly listed above as Apache-2.0, which misstated their redistribution terms. Read the licence
from the artifact's POM rather than assuming a Google-published library is Apache-2.0: several are, and
these are not.

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
